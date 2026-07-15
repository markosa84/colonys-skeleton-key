import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

/**
 * What, physically, separates a brass pin from the steel plate it sits on - and does that survive
 * the gamma slider and an HDR tonemap?
 *
 * <pre>
 *   java tools/PinPixels.java
 * </pre>
 *
 * <p>{@code LockReader.isPin} answers with six absolute numbers ({@code r >= 130}, {@code b <= 140},
 * {@code (r - b) >= 45} ...) and they are only true for one screen. Before replacing them with a
 * frame-relative rule, this prints the populations they were fitted to: the pin's own pixels, and the
 * plate ring around it, per frame, per plate. Every candidate invariant is printed beside the raw
 * channels, so the choice is made from the numbers rather than from a story about tonemaps.
 */
public final class PinPixels {

    static final double FAN_X = 3090.0, FAN_Y = 798.0;
    static final double DEPTH_X = 58.9, DEPTH_Y = -36.75;
    /** The pin's own body, and the plate around it. Reference (4K) radii. */
    static final double CORE = 9.0, RING_IN = 30.0, RING_OUT = 44.0;

    static final String F = "src/test/data/frames/";

    record Shot(String path, int n, int[] offsets, String note) {}

    public static void main(String[] args) throws Exception {
        List<Shot> shots = List.of(
                new Shot(F + "gamma/g-2.7.png", 7, chest(0), "CALIBRATED (gamma 2.7)"),
                new Shot(F + "gamma/g-1.2/step-0.png", 7, chest(0), "gamma 1.2 (darkest)"),
                new Shot(F + "gamma/g-3.2/step-0.png", 7, chest(0), "gamma 3.2 (brightest)"),
                new Shot(F + "3840x2160/front-plate-sweep/step-3.png", 5,
                        new int[] {3, 1, 2, 0, 0}, "5-plate, 4K, plates 3+4 popped"),
                new Shot(F + "800x600/front-plate-sweep/step-3.png", 5,
                        new int[] {3, 1, 2, 0, 0}, "5-plate, 800x600"),
                new Shot("captures/1/no-lock-20260713-151812-209.png", 5, null, "HDR 2560x1440"),
                new Shot("captures/2/no-lock-20260714-010246-975.png", 5, null, "HDR 1920x1080"));

        for (Shot s : shots) {
            File file = new File(s.path());
            if (!file.isFile()) {
                System.out.println("# missing " + s.path() + "\n");
                continue;
            }
            BufferedImage img = ImageIO.read(file);
            View v = new View(img.getWidth(), img.getHeight());
            System.out.printf(Locale.ROOT, "=== %s  [%s]  %dx%d ===%n",
                    s.path(), s.note(), img.getWidth(), img.getHeight());
            Box box = Box.of(img, v);
            System.out.printf(Locale.ROOT,
                    "  pin box: lum p50 %3d  p90 %3d  p98 %3d | r-b p50 %3d  p90 %3d  p98 %3d"
                    + " | STEEL (lit, neutral): lum %3d  r-b %2d%n",
                    box.lumP50, box.lumP90, box.lumP98, box.rbP50, box.rbP90, box.rbP98,
                    box.steelLum, box.steelRb);
            System.out.printf(Locale.ROOT, "%-6s %-4s | %-19s | %-19s | %s%n",
                    "plate", "", "PIN core (median)", "PLATE ring (median)", "separation");
            System.out.printf(Locale.ROOT, "%-6s %-4s | %-19s | %-19s | %s%n",
                    "", "", "r    g    b   lum  chr", "r    g    b   lum  chr",
                    "d(r-b)   chr ratio   lum ratio");
            for (int i = 0; i < s.n(); i++) {
                Px core = sample(img, v, s.n(), i, 0, v.len(CORE));
                Px ring = sample(img, v, s.n(), i, v.len(RING_IN), v.len(RING_OUT));
                String mark = s.offsets() == null ? "  ?  " : (s.offsets()[i] == 0 ? " POP " : " --  ");
                System.out.printf(Locale.ROOT,
                        "%-6d%-5s| %3d %4d %4d %4d %5.2f | %3d %4d %4d %4d %5.2f | %6d %9.2f %10.2f%n",
                        i, mark,
                        core.r, core.g, core.b, core.lum, core.chroma,
                        ring.r, ring.g, ring.b, ring.lum, ring.chroma,
                        (core.r - core.b) - (ring.r - ring.b),
                        ring.chroma <= 0.01 ? 99 : core.chroma / ring.chroma,
                        ring.lum == 0 ? 99 : core.lum / (double) ring.lum);
            }
            System.out.println();
        }
    }

    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }

    /** The pin box's own statistics: the pool any frame-relative threshold would have to come from. */
    record Box(int lumP50, int lumP90, int lumP98, int rbP50, int rbP90, int rbP98,
            int steelLum, int steelRb) {

        static final int RX0 = 2650, RY0 = 620, RX1 = 3520, RY1 = 1000;

        static Box of(BufferedImage img, View v) {
            int x0 = clamp((int) v.x(RX0), 0, img.getWidth());
            int x1 = clamp((int) v.x(RX1), 0, img.getWidth());
            int y0 = clamp((int) v.y(RY0), 0, img.getHeight());
            int y1 = clamp((int) v.y(RY1), 0, img.getHeight());
            int[] lumHist = new int[256];
            int[] rbHist = new int[256];
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int argb = img.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    lumHist[(int) (0.299 * r + 0.587 * g + 0.114 * b)]++;
                    rbHist[Math.max(0, Math.min(255, r - b))]++;
                }
            }
            int lumP50 = pct(lumHist, 0.50);
            // The steel: lit (above the box median) and neutral (not brass, not wood). This is the
            // plate itself, and it is the only large man-made surface in the box.
            List<Integer> steelLums = new ArrayList<>(), steelRbs = new ArrayList<>();
            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int argb = img.getRGB(x, y);
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
                    if (lum > lumP50 && Math.abs(r - b) < 15) {
                        steelLums.add(lum);
                        steelRbs.add(r - b);
                    }
                }
            }
            return new Box(lumP50, pct(lumHist, 0.90), pct(lumHist, 0.98),
                    pct(rbHist, 0.50), pct(rbHist, 0.90), pct(rbHist, 0.98),
                    median(steelLums), median(steelRbs));
        }
    }

    static int pct(int[] hist, double p) {
        long total = 0;
        for (int c : hist) {
            total += c;
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

    record Px(int r, int g, int b, int lum, double chroma) {}

    /** Median channel values over the annulus {@code [inner, outer]} about plate {@code i}'s pin. */
    static Px sample(BufferedImage img, View v, int n, int i, double inner, double outer) {
        double[] p = pin(v, n, i);
        List<Integer> rs = new ArrayList<>(), gs = new ArrayList<>(), bs = new ArrayList<>();
        int x0 = clamp((int) (p[0] - outer), 0, img.getWidth());
        int x1 = clamp((int) (p[0] + outer) + 1, 0, img.getWidth());
        int y0 = clamp((int) (p[1] - outer), 0, img.getHeight());
        int y1 = clamp((int) (p[1] + outer) + 1, 0, img.getHeight());
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                double dist = Math.hypot(x - p[0], y - p[1]);
                if (dist < inner || dist > outer) {
                    continue;
                }
                int argb = img.getRGB(x, y);
                rs.add((argb >> 16) & 0xFF);
                gs.add((argb >> 8) & 0xFF);
                bs.add(argb & 0xFF);
            }
        }
        int r = median(rs), g = median(gs), b = median(bs);
        int lum = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        return new Px(r, g, b, lum, Math.max(0, r - b) / (double) Math.max(1, lum));
    }

    static int median(List<Integer> xs) {
        if (xs.isEmpty()) {
            return 0;
        }
        Collections.sort(xs);
        return xs.get(xs.size() / 2);
    }

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
}
