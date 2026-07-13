import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Regenerates {@code vision/Tone}'s curve family from the labelled gamma frames. The table it prints
 * is pasted straight into {@code Tone.FAMILY} - never hand-edit those numbers, come back here.
 *
 * <pre>
 *   java tools/ToneTable.java  src/test/data/frames/7p-plate-2-sweep/step-0.png \
 *                              src/test/data/frames/gamma/g-2.7.png \
 *                              src/test/data/frames/gamma/g-1.2/step-0.png \
 *                              src/test/data/frames/gamma/g-1.5.png  ... etc, in slider order
 * </pre>
 *
 * <p><b>arg 0</b> is the calibrated fixture - the look everything is mapped back to. <b>arg 1</b> is
 * a SECOND capture at the calibrated gamma, from a different opening of the chest; it is not a curve,
 * it is the <i>mask</i> (see {@link #stable}). The rest are one frame per gamma setting, all of the
 * same lock in the same state.
 *
 * <h2>Why the curves are measured and not fitted</h2>
 * A two-parameter {@code gain x power} curve was fitted through the panel's dark anchors first, and
 * it fails: extrapolated to the bright end it maps observed 255 back to 179, crushes the pin
 * highlights, and leaves one detected pin where the raw frame had seven. Two closely-spaced dark
 * anchors say nothing about the far end of the range.
 *
 * <p>So every level is measured instead. The frames are the same lock in the same state at two
 * gammas, which makes each pixel an {@code (observed, calibrated)} sample; the median per level is
 * the curve. The transform is a plain per-pixel LUT on the final frame - checked, per channel, with
 * an inter-quartile spread of 0-3 levels - so a LUT is exactly what inverts it.
 *
 * <p>The quality metric is printed and it is exact: the frame captured at the <b>calibrated</b>
 * gamma must come back as the identity. If it does not, the fit is picking up something that is not
 * the gamma, and the numbers are not to be trusted.
 */
public final class ToneTable {

    static final int LOCK_X0 = 2450, LOCK_Y0 = 300, LOCK_W = 1300, LOCK_H = 1120;
    static final int PICKS_X0 = 3104, PICKS_Y0 = 1616, PICKS_W = 72, PICKS_H = 56;

    /** Knots in reference space: every 16 levels, plus the top. */
    static final int KNOT_STEP = 16;
    static final int KNOTS = 17; // 0, 16, ... 240, 255

    /** Pixels that mean the same thing in two different openings of the chest. */
    static boolean[] stable;

    public static void main(String[] args) throws Exception {
        BufferedImage ref = ImageIO.read(new File(args[0]));

        // The mask. args[1] is a SECOND capture at the calibrated gamma, from a different opening of
        // the chest: wherever it disagrees with the fixture, the pixel is not the lock - it is the
        // room behind it, frozen at a different phase of its flickering light. Those pixels carry no
        // correspondence, they are concentrated in the dark levels, and left in they drag the fit 18
        // levels off identity in exactly the band the hole thresholds live in.
        BufferedImage same = ImageIO.read(new File(args[1]));
        stable = new boolean[3840 * 2160];
        int kept = 0, total = 0;
        for (int y = LOCK_Y0; y < LOCK_Y0 + LOCK_H; y++) {
            for (int x = LOCK_X0; x < LOCK_X0 + LOCK_W; x++) {
                int a = ref.getRGB(x, y), b = same.getRGB(x, y);
                boolean ok = true;
                for (int shift = 0; shift <= 16; shift += 8) {
                    ok &= Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)) <= 2;
                }
                stable[y * 3840 + x] = ok;
                total++;
                if (ok) {
                    kept++;
                }
            }
        }
        System.out.printf("mask: %d of %d lock-box pixels are stable across two openings (%.0f%%)%n%n",
                kept, total, 100.0 * kept / total);

        for (int a = 2; a < args.length; a++) {
            File f = new File(args[a]);
            BufferedImage obs = ImageIO.read(f);
            int[] panel = panel(obs);
            int ink = mode(panel, 0, 160);
            int white = mode(panel, 160, 256);
            int[] lut = lut(ref, obs); // observed -> calibrated: the ground truth

            // The quality metric: how far this curve sits from the identity. For the frame captured
            // at the CALIBRATED gamma this must come out at ~0 - anything else is measurement
            // error, and it would be error in exactly the band the hole thresholds live in.
            int fromIdentity = 0;
            for (int o = 0; o < 256; o++) {
                if (lut[o] >= 40 && lut[o] <= 160) {
                    fromIdentity = Math.max(fromIdentity, Math.abs(lut[o] - o));
                }
            }
            System.out.printf("        // measured %s: off the identity by %d in the threshold "
                    + "band%n", f.getName(), fromIdentity);
            System.out.printf("        new Curve(%d, %d, \"\"\"%n", ink, white);
            for (int row = 0; row < 16; row++) {
                StringBuilder line = new StringBuilder("                ");
                for (int col = 0; col < 16; col++) {
                    line.append(String.format("%4d", lut[row * 16 + col]));
                    if (row * 16 + col < 255) {
                        line.append(',');
                    }
                }
                System.out.println(line);
            }
            System.out.println("                \"\"\"),");
        }
    }

    /**
     * Worst error, over the threshold band, of storing the LUT only every {@code step} observed
     * levels and interpolating the rest. This is what decides how the shipped table is stored.
     */
    static int error(int[] lut, int step) {
        int[] sparse = new int[256];
        boolean[] known = new boolean[256];
        for (int o = 0; o < 256; o += step) {
            sparse[o] = lut[o];
            known[o] = true;
        }
        sparse[255] = lut[255];
        known[255] = true;
        int[] rebuilt = fill(sparse, known);

        int worst = 0;
        for (int o = 0; o < 256; o++) {
            if (lut[o] >= 40 && lut[o] <= 160) {
                worst = Math.max(worst, Math.abs(rebuilt[o] - lut[o]));
            }
        }
        return worst;
    }

    /** Inverts a monotone map. Where several inputs share an output, the midpoint is taken. */
    static int[] invert(int[] map) {
        int[] out = new int[256];
        boolean[] known = new boolean[256];
        for (int v = 0; v < 256; v++) {
            int lo = -1, hi = -1;
            for (int u = 0; u < 256; u++) {
                if (map[u] == v) {
                    if (lo < 0) {
                        lo = u;
                    }
                    hi = u;
                }
            }
            if (lo >= 0) {
                out[v] = (lo + hi) / 2;
                known[v] = true;
            }
        }
        return fill(out, known);
    }

    /** Levels nothing maps to: linear interpolation between the ones that do, then monotone. */
    static int[] fill(int[] out, boolean[] known) {
        int prev = -1;
        for (int v = 0; v < 256; v++) {
            if (!known[v]) {
                continue;
            }
            if (prev >= 0) {
                for (int u = prev + 1; u < v; u++) {
                    out[u] = out[prev] + (out[v] - out[prev]) * (u - prev) / (v - prev);
                }
            }
            prev = v;
        }
        for (int v = prev + 1; v < 256; v++) {
            out[v] = out[prev];
        }
        for (int v = 1; v < 256; v++) {
            out[v] = Math.max(out[v], out[v - 1]);
        }
        return out;
    }

    /**
     * The observed->calibrated curve, fitted robustly.
     *
     * <p>The two frames are the same lock in the same state, but they are different <i>openings</i>
     * of the chest, and the minigame freezes the world at whatever phase it was in - so the room
     * behind the lock (its flickering light) does not correspond, while the lock itself, statically
     * lit, does. Those background pixels are a minority but they sit in the dark levels, which is
     * exactly where the hole thresholds live. So: fit the median, then throw out every pixel the fit
     * cannot explain and fit again. What survives is the transfer curve; what is discarded is the
     * room.
     */
    static int[] lut(BufferedImage ref, BufferedImage obs) {
        int[][] pairs = new int[256][256]; // [observed][calibrated] -> count
        for (int y = LOCK_Y0; y < LOCK_Y0 + LOCK_H; y++) {
            for (int x = LOCK_X0; x < LOCK_X0 + LOCK_W; x++) {
                if (!stable[y * 3840 + x]) {
                    continue;
                }
                int r = ref.getRGB(x, y), o = obs.getRGB(x, y);
                for (int shift = 0; shift <= 16; shift += 8) {
                    pairs[(o >> shift) & 0xFF][(r >> shift) & 0xFF]++;
                }
            }
        }
        int[] lut = median(pairs, null);
        for (int pass = 0; pass < 3; pass++) {
            lut = median(pairs, lut); // reject what the previous fit could not explain
        }
        return smooth(lut);
    }

    /**
     * The transfer curve is smooth - it is a tone curve, not a lookup of unrelated numbers - so a
     * level-to-level wobble in the fit is measurement noise, not signal. Average it out, then force
     * monotone again.
     */
    static int[] smooth(int[] lut) {
        int[] out = new int[256];
        for (int v = 0; v < 256; v++) {
            int lo = Math.max(0, v - 2), hi = Math.min(255, v + 2);
            int sum = 0;
            for (int u = lo; u <= hi; u++) {
                sum += lut[u];
            }
            out[v] = Math.round((float) sum / (hi - lo + 1));
        }
        out[0] = 0;
        out[255] = lut[255];
        for (int v = 1; v < 256; v++) {
            out[v] = Math.max(out[v], out[v - 1]);
        }
        return out;
    }

    /** Per observed level, the median calibrated level - ignoring pixels far from {@code prior}. */
    static int[] median(int[][] pairs, int[] prior) {
        int[] lut = new int[256];
        boolean[] known = new boolean[256];
        for (int o = 0; o < 256; o++) {
            long total = 0;
            for (int r = 0; r < 256; r++) {
                if (prior == null || Math.abs(r - prior[o]) <= 12) {
                    total += pairs[o][r];
                }
            }
            if (total < 100) {
                continue;
            }
            long half = total / 2;
            long acc = 0;
            for (int r = 0; r < 256; r++) {
                if (prior != null && Math.abs(r - prior[o]) > 12) {
                    continue;
                }
                acc += pairs[o][r];
                if (acc >= half) {
                    lut[o] = r;
                    known[o] = true;
                    break;
                }
            }
        }
        lut[0] = 0;
        known[0] = true;
        if (!known[255]) {
            lut[255] = 255;
            known[255] = true;
        }
        return fill(lut, known);
    }

    static int mode(int[] h, int lo, int hi) {
        int mode = 0, best = 0;
        for (int v = lo; v < hi; v++) {
            if (h[v] > best) {
                best = h[v];
                mode = v;
            }
        }
        return mode;
    }

    static int[] panel(BufferedImage img) {
        double scale = Math.min(img.getWidth() / 3840.0, img.getHeight() / 2160.0);
        int x0 = (int) Math.floor(img.getWidth() / 2.0 + (PICKS_X0 - 1920) * scale);
        int y0 = (int) Math.floor(img.getHeight() / 2.0 + (PICKS_Y0 - 1080) * scale);
        int x1 = (int) Math.ceil(x0 + PICKS_W * scale);
        int y1 = (int) Math.ceil(y0 + PICKS_H * scale);
        int[] h = new int[256];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int p = img.getRGB(x, y);
                h[(int) (0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF)
                        + 0.114 * (p & 0xFF))]++;
            }
        }
        return h;
    }
}
