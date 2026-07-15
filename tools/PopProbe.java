import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * Measures the <b>pin-pop</b> signal - the one thing an alternative, photometry-free reader cannot get
 * from the hole rows, and the only signal {@code LockSession} may declare a lock open from.
 *
 * <pre>
 *   java tools/PopProbe.java
 * </pre>
 *
 * <h2>What the pixels say (measured; see {@code tools/PinPixels.java})</h2>
 *
 * The pin is brass, the plate is steel, and that is a <b>hue</b> difference no monotone tone curve can
 * take away. Median {@code (r - b)}, and median luminance, of each:
 *
 * <pre>
 *                      pin r-b     steel r-b     pin lum      steel lum
 *   calibrated 2.7      48..66          0        157..198        241
 *   gamma 1.2           32..49          4         35..68          46
 *   gamma 3.2           51..64          0        186..228        255
 *   HDR 2560x1440       20..38          2         56..78          36
 *   HDR 1920x1080       25..39          2         73..86          42
 * </pre>
 *
 * So {@code isPin}'s {@code (r - b) >= 45} is set <b>too high</b> - an HDR frame's pins live at 20-39
 * and fall straight through it. That is the HDR bug in one number.
 *
 * <h2>Two gates, and both must be relative</h2>
 *
 * The pop is a <b>size</b> signal: the pin rises toward the camera and its brass roughly doubles
 * (4K: flat 150-183px, popped 343-446px). To see that, the mask must isolate brass, and brass has two
 * neighbours to be told apart from:
 *
 * <ul>
 *   <li><b>the steel plate</b> - lit, but neutral. A warmth gate rejects it.</li>
 *   <li><b>the holes</b> - the dark room showing through the plate, which is warm <i>brown</i>. Only a
 *       brightness gate rejects those, and that is the job {@code isPin}'s {@code r >= 130} was really
 *       doing. Drop it and the count triples.</li>
 * </ul>
 *
 * <p>Both gates are therefore drawn from the frame. The brightness reference is the <b>steel's own
 * luminance</b> - the plates are the one large lit man-made surface in the box, and every pin sits
 * above a fraction of them at every exposure measured (241 -> 46 -> 255 -> 36 as the frame darkens,
 * with the pins tracking it). The warmth reference is a high percentile of {@code (r - b)} among the
 * lit pixels, which lands in this frame's own brass.
 *
 * <p>Dead ends already measured, so they are not tried again:
 * <ul>
 *   <li><b>Otsu over the box</b> splits black from not-black ({@code r-b} ~15) - the plates read warm.</li>
 *   <li><b>Otsu over the lit pixels</b> splits the steel population against itself: brass is far too
 *       small a minority for Otsu, which wants balanced classes.</li>
 *   <li><b>A gate taken from the disc being measured</b> inverts the signal - a popped pin's disc holds
 *       more bright pixels, so its own median rises, so the gate tightens, so it counts fewer. Popped
 *       came out 0.7x the size of flat. Never normalise by the thing you are measuring.</li>
 *   <li><b>Chroma {@code (r-b)/lum}</b> is not invariant: gamma is a power curve, so the same pins run
 *       0.24 (gamma 3.2) to 0.91 (gamma 1.2).</li>
 * </ul>
 *
 * <p>The ruler is the lock's own hole spacing {@code d}: areas go as {@code d^2}, so {@code area/d^2}
 * is dimensionless and the resolution drops out.
 */
public final class PopProbe {

    // --- The measured 4K fan geometry, copied from vision/LockReader (this is a probe, not the app).
    static final double FAN_X = 3090.0, FAN_Y = 798.0;
    static final double DEPTH_X = 58.9, DEPTH_Y = -36.75;
    static final int RX0 = 2650, RY0 = 620, RX1 = 3520, RY1 = 1000;
    /** Hole spacing at 4K: the ruler. */
    static final double STEP_IDEAL = 48.0;
    /** The disc a pin is measured in. Adjacent pins sit 69px apart, so this stays clear of the
     *  neighbours while holding the whole cap of a popped pin (whose centroid rides ~10px high). */
    static final double CAP_RADIUS = 30.0;
    /** How far a blob's centroid may sit from the fan position and still be that plate's pin.
     *  LockReader's MATCH_MAX_DIST; a popped pin's centroid rides ~10px high, well inside it. */
    static final double MATCH_MAX_DIST = 26.0;

    static final String FRAMES = "src/test/data/frames/";

    record Shot(String path, int n, int[] offsets) {}

    record Sample(double norm, String where) {}

    public static void main(String[] args) throws Exception {
        List<Shot> shots = shots();
        System.out.println("=== Can two frame-relative gates see the pop? ===");
        System.out.println("area/d^2 over " + shots.size()
                + " labelled frames: 800x600..4K, gamma 1.2..3.2, 5- and 7-plate locks.\n");
        System.out.println("  lit   = lum > LIT * (this frame's steel luminance)");
        System.out.println("  warm  = (r-b) >= WARM * p99(r-b among lit pixels in the pin box)\n");
        System.out.printf(Locale.ROOT, "%-5s %-5s %-9s %-9s %-9s %-9s  %s%n",
                "LIT", "WARM", "flat p50", "flat MAX", "POP MIN", "POP p50", "verdict");

        double[] lits = {0.0, 0.30, 0.40, 0.50, 0.60};
        double[] warms = {0.40, 0.50, 0.60, 0.70, 0.80};
        Best best = null;
        for (double lit : lits) {
            for (double warm : warms) {
                List<Sample> flat = new ArrayList<>();
                List<Sample> popped = new ArrayList<>();
                for (Shot s : shots) {
                    File file = new File(s.path());
                    if (!file.isFile()) {
                        continue;
                    }
                    BufferedImage img = ImageIO.read(file);
                    View v = new View(img.getWidth(), img.getHeight());
                    Stats stats = Stats.of(img, v, lit, s.n());
                    double d = v.len(STEP_IDEAL);
                    for (int i = 0; i < s.n(); i++) {
                        double norm = area(img, v, s.n(), i, stats, warm) / (d * d);
                        Sample sample = new Sample(norm,
                                s.path().substring(FRAMES.length()) + " plate " + i);
                        (s.offsets()[i] == 0 ? popped : flat).add(sample);
                    }
                }
                flat.sort((a, b) -> Double.compare(a.norm, b.norm));
                popped.sort((a, b) -> Double.compare(a.norm, b.norm));
                double worstFlat = flat.get(flat.size() - 1).norm;
                double worstPop = popped.get(0).norm;
                boolean separates = worstPop > worstFlat;
                double margin = worstPop / Math.max(worstFlat, 1e-9);
                // The same question again, with 800x600 set aside. At that scale a pin is SEVEN
                // pixels, and this room has a warm something sitting exactly on the back plate's fan
                // position - CLAUDE.md measures it at up to 14.8x the pin-size floor. That is a
                // sampling-density limit, not a photometric one, and it is worth knowing whether the
                // rule is sound above it.
                List<Sample> flatBig = flat.stream().filter(x -> !x.where.startsWith("800x600")).toList();
                List<Sample> popBig = popped.stream().filter(x -> !x.where.startsWith("800x600")).toList();
                double worstFlatBig = flatBig.get(flatBig.size() - 1).norm;
                double worstPopBig = popBig.get(0).norm;
                boolean sepBig = worstPopBig > worstFlatBig;
                System.out.printf(Locale.ROOT, "%-5.2f %-5.2f %-9.4f %-9.4f %-9.4f %-9.4f  %-38s | >=1024x768: %s%n",
                        lit, warm, q(flat, 0.5), worstFlat, worstPop, q(popped, 0.5),
                        separates
                                ? String.format(Locale.ROOT, "SEPARATES cut %.3f margin %.2fx",
                                        Math.sqrt(worstFlat * worstPop), margin)
                                : "overlaps: " + flat.get(flat.size() - 1).where,
                        sepBig
                                ? String.format(Locale.ROOT, "SEPARATES cut %.3f margin %.2fx",
                                        Math.sqrt(worstFlatBig * worstPopBig), worstPopBig / worstFlatBig)
                                : "overlaps: " + flatBig.get(flatBig.size() - 1).where);
                if (sepBig && (best == null || worstPopBig / worstFlatBig > best.margin)) {
                    best = new Best(lit, warm, worstFlatBig, worstPopBig, worstPopBig / worstFlatBig);
                }
            }
        }

        if (best == null) {
            System.out.println("\nNothing separates. The pop needs a different observable"
                    + " - see the fallback in the plan (cap height above the row line).");
            return;
        }
        System.out.printf(Locale.ROOT,
                "%n=== WINNER: lit %.2f, warm %.2f - worst flat %.4f, worst popped %.4f,"
                + " margin %.2fx, cut at %.3f ===%n",
                best.lit, best.warm, best.worstFlat, best.worstPop, best.margin,
                Math.sqrt(best.worstFlat * best.worstPop));

        // The frames the current reader cannot read at all: does the winning rule make them bimodal?
        System.out.println("\n=== HDR dumps (the current reader returns -1 on every one of these) ===");
        for (String hdr : new String[] {
                "captures/1/no-lock-20260713-151812-209.png",
                "captures/1/no-lock-20260713-152308-930.png",
                "captures/2/no-lock-20260714-010246-975.png",
                "captures/2/no-lock-20260714-010320-202.png"}) {
            File file = new File(hdr);
            if (!file.isFile()) {
                System.out.println("# missing " + hdr);
                continue;
            }
            BufferedImage img = ImageIO.read(file);
            View v = new View(img.getWidth(), img.getHeight());
            Stats stats = Stats.of(img, v, best.lit, 5);
            double d = v.len(STEP_IDEAL);
            double cut = Math.sqrt(best.worstFlat * best.worstPop);
            StringBuilder row = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                double norm = area(img, v, 5, i, stats, best.warm) / (d * d);
                row.append(String.format(Locale.ROOT, "%s%.3f%s", i > 0 ? "  " : "", norm,
                        norm >= cut ? "*" : " "));
            }
            System.out.printf(Locale.ROOT, "  %-46s steel lum %3d  n=5 [%s]%n",
                    hdr.substring(hdr.lastIndexOf('/') + 1), stats.steelLum, row);
        }
        System.out.println("  ('*' = reads as popped, i.e. that plate is centred)");
    }

    record Best(double lit, double warm, double worstFlat, double worstPop, double margin) {}

    static List<Shot> shots() {
        List<Shot> shots = new ArrayList<>();
        // The 7-plate chest across the game's gamma slider. Plate 0 is centred throughout; plate 2
        // sweeps its own track and is centred at step 3.
        for (int k = 0; k < 7; k++) {
            shots.add(new Shot(FRAMES + "gamma/g-1.2/step-" + k + ".png", 7, chest(k)));
            shots.add(new Shot(FRAMES + "gamma/g-3.2/step-" + k + ".png", 7, chest(k)));
            shots.add(new Shot(FRAMES + "2560x1440/gamma-1.2-sweep/step-" + k + ".png", 7, chest(k)));
        }
        for (String g : new String[] {"1.5", "1.8", "2.1", "2.4", "2.7", "3.0"}) {
            shots.add(new Shot(FRAMES + "gamma/g-" + g + ".png", 7, chest(0)));
        }
        // The 5-plate lock across the resolution range. Plate 3 is centred throughout; the front
        // plate sweeps and is centred at step 3.
        for (String mode : new String[] {"3840x2160", "2560x1440", "1920x1080", "1280x720", "800x600"}) {
            for (int k = 0; k < 7; k++) {
                shots.add(new Shot(FRAMES + mode + "/front-plate-sweep/step-" + k + ".png", 5,
                        new int[] {3, 1, 2, 0, 3 - k}));
            }
        }
        return shots;
    }

    /** The 7-plate chest at gamma-sweep step k: plate 2 walks its track, nothing else moves. */
    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }

    // --- pixels ----------------------------------------------------------------------------------

    static int rb(int argb) {
        return Math.max(0, ((argb >> 16) & 0xFF) - (argb & 0xFF));
    }

    static int lum(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }

    /**
     * Everything the mask is allowed to know, and all of it read off this frame.
     *
     * @param steelLum  median luminance of the plates: lit, and neutral in hue. The one large
     *                  man-made surface in the box, and the brightness the pins are judged against.
     * @param litAbove  {@code lit * steelLum} - the brightness gate that rejects the dark warm holes.
     * @param rbP99Lit  99th percentile of {@code (r - b)} among the lit pixels: this frame's brass.
     */
    record Stats(int steelLum, int litAbove, int rbP99Lit) {

        static Stats of(BufferedImage img, View v, double lit, int n) {
            int x0 = clamp((int) v.x(RX0), 0, img.getWidth());
            int x1 = clamp((int) v.x(RX1), 0, img.getWidth());
            int y0 = clamp((int) v.y(RY0), 0, img.getHeight());
            int y1 = clamp((int) v.y(RY1), 0, img.getHeight());
            int[] lumHist = new int[256];
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    lumHist[lum(img.getRGB(x, y))]++;
                }
            }
            int lumMedian = pct(lumHist, 0.50);
            // The steel: lit, and neutral in hue. Brass and the warm room are both excluded by the
            // hue test; the dark half of the box by the brightness one.
            List<Integer> steel = new ArrayList<>();
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int argb = img.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF, b = argb & 0xFF;
                    if (lum(argb) > lumMedian && Math.abs(r - b) < 15) {
                        steel.add(lum(argb));
                    }
                }
            }
            int steelLum = median(steel);
            int litAbove = (int) (lit * steelLum);
            return new Stats(steelLum, litAbove, brass(img, v, n, litAbove));
        }

        /**
         * This lock's own brass, in this frame's own exposure.
         *
         * <p>A percentile over the pin box was tried and it is contaminated: the box holds the room
         * as well as the lock, and a room is full of warm wood and candlelight - so p99 lands on a
         * candle, the threshold goes through the roof, and popped pins measure <b>zero</b>. But we
         * are not reduced to guessing where the brass is: {@code pinPosition} says exactly where the
         * n pins are, and they are brass by construction. Their own peak warmth is therefore the
         * reference, and nothing in the room can move it.
         *
         * <p>It is a <b>shared</b> reference - one number for all n plates - which is what keeps it
         * from normalising away the pop. Each plate's blob is then measured against the same bar, and
         * a popped one is bigger. (Per-pin thresholds were tried and they invert the signal.)
         */
        static int brass(BufferedImage img, View v, int n, int litAbove) {
            List<Integer> peaks = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double[] p = pin(v, n, i);
                double rad = v.len(CAP_RADIUS);
                int[] hist = new int[256];
                int x0 = clamp((int) (p[0] - rad), 0, img.getWidth());
                int x1 = clamp((int) (p[0] + rad) + 1, 0, img.getWidth());
                int y0 = clamp((int) (p[1] - rad), 0, img.getHeight());
                int y1 = clamp((int) (p[1] + rad) + 1, 0, img.getHeight());
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int argb = img.getRGB(x, y);
                        if (Math.hypot(x - p[0], y - p[1]) <= rad && lum(argb) > litAbove) {
                            hist[rb(argb)]++;
                        }
                    }
                }
                peaks.add(pct(hist, 0.90));
            }
            return median(peaks);
        }
    }

    /**
     * The size of the brass <b>blob</b> at plate {@code i}'s fan position - a connected component, not
     * a count of warm pixels in a disc.
     *
     * <p>The distinction matters and it is not pedantic: a disc count also picks up whatever else in
     * the disc happens to be warm and lit (a rivet, a highlight on the plate's rim, the glow around
     * the pin), and that noise is roughly the same size for a flat pin as for a popped one - so it
     * lands entirely on top of the signal. The pop is a growth of ONE connected object, so that is
     * what has to be measured. It is also what {@code LockReader} already does, which is why its
     * absolute gate manages a clean 2.4x at the calibrated gamma.
     */
    static int area(BufferedImage img, View v, int n, int i, Stats stats, double warm) {
        double[] p = pin(v, n, i);
        double rad = v.len(CAP_RADIUS);
        double reach = v.len(MATCH_MAX_DIST);
        int warmAbove = (int) (warm * stats.rbP99Lit);
        int x0 = clamp((int) (p[0] - rad), 0, img.getWidth());
        int x1 = clamp((int) (p[0] + rad) + 1, 0, img.getWidth());
        int y0 = clamp((int) (p[1] - rad), 0, img.getHeight());
        int y1 = clamp((int) (p[1] + rad) + 1, 0, img.getHeight());
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0) {
            return 0;
        }
        boolean[] warmPx = new boolean[w * h];
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int argb = img.getRGB(x, y);
                warmPx[(y - y0) * w + (x - x0)] =
                        lum(argb) > stats.litAbove && rb(argb) >= warmAbove;
            }
        }
        // The biggest connected blob whose centroid lands near the fan position - the same question
        // LockReader's matchPin asks, and the same answer.
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        int best = 0;
        for (int seed = 0; seed < w * h; seed++) {
            if (!warmPx[seed] || seen[seed]) {
                continue;
            }
            int sp = 0;
            stack[sp++] = seed;
            seen[seed] = true;
            int size = 0;
            long sumX = 0, sumY = 0;
            while (sp > 0) {
                int q = stack[--sp];
                int qx = q % w, qy = q / w;
                size++;
                sumX += qx;
                sumY += qy;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = qx + dx, ny = qy + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int r = ny * w + nx;
                        if (warmPx[r] && !seen[r]) {
                            seen[r] = true;
                            stack[sp++] = r;
                        }
                    }
                }
            }
            double cx = x0 + sumX / (double) size;
            double cy = y0 + sumY / (double) size;
            if (Math.hypot(cx - p[0], cy - p[1]) <= reach) {
                best = Math.max(best, size);
            }
        }
        return best;
    }

    static int median(List<Integer> xs) {
        if (xs.isEmpty()) {
            return 1;
        }
        Collections.sort(xs);
        return xs.get(xs.size() / 2);
    }

    static int pct(int[] hist, double p) {
        long total = 0;
        for (int c : hist) {
            total += c;
        }
        if (total == 0) {
            return 0;
        }
        long want = (long) Math.ceil(p * total);
        long seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= want) {
                return i;
            }
        }
        return 255;
    }

    // --- geometry --------------------------------------------------------------------------------

    /** The viewport mapping, copied from vision/Viewport: an aspect-fit about the view's centre. */
    static final class View {
        final int w, h;
        final double scale;

        View(int w, int h) {
            this.w = w;
            this.h = h;
            this.scale = Math.min(w / 3840.0, h / 2160.0);
        }

        double x(double rx) {
            return w / 2.0 + (rx - 1920.0) * scale;
        }

        double y(double ry) {
            return h / 2.0 + (ry - 1080.0) * scale;
        }

        double len(double l) {
            return l * scale;
        }
    }

    static double[] pin(View v, int n, int i) {
        double mid = (n - 1) / 2.0;
        return new double[] {v.x(FAN_X + (mid - i) * DEPTH_X), v.y(FAN_Y + (mid - i) * DEPTH_Y)};
    }

    static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double q(List<Sample> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i))).norm;
    }
}
