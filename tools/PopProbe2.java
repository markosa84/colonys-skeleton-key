import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

import io.github.markosa84.colonysskeletonkey.vision.LatticeReader;
import io.github.markosa84.colonysskeletonkey.vision.Tone;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;

/**
 * Which pop observable separates a centred pin from an off-centre one best?
 *
 * <pre>
 *   java -cp build/classes/java/main tools/PopProbe2.java
 * </pre>
 *
 * <p>The pin-pop is the only signal {@code LockSession} may declare a lock open from, and the single
 * "how dark is the pin's own column" reading {@link LatticeReader} uses today misreads 1.5% of pins -
 * its clouds cross at the tails. This measures a battery of candidate observables (see
 * {@code LatticeReader.popFeatures}) over the whole labelled corpus and reports each one's best single
 * cut, so the winner is chosen from the numbers rather than a story. It also names the pins each
 * candidate gets wrong, because a signal is judged at its tails, not its median.
 */
public final class PopProbe2 {

    static final String FRAMES = "src/test/data/frames/";

    static final String[] NAMES = {"pinDark (own plate)", "discDark (frame range)"};

    record Sample(double v, boolean popped, String where) {}

    public static void main(String[] args) throws Exception {
        List<List<Sample>> byFeature = new ArrayList<>();
        for (int f = 0; f < NAMES.length; f++) {
            byFeature.add(new ArrayList<>());
        }
        for (Shot s : corpus()) {
            BufferedImage img = read(s.path());
            if (img == null) {
                continue;
            }
            Viewport vp = new Viewport(img.getWidth(), img.getHeight());
            LatticeReader r = new LatticeReader(vp, Tone.estimate(img, vp));
            List<double[]> feats = r.popFeatures(img, s.n());
            for (int i = 0; i < s.n(); i++) {
                double[] f = feats.get(i);
                boolean pop = s.offsets()[i] == 0;
                String where = s.path().substring(FRAMES.length()) + " plate " + i;
                for (int c = 0; c < NAMES.length; c++) {
                    byFeature.get(c).add(new Sample(f[c], pop, where));
                }
            }
        }

        System.out.println("=== Each candidate pop observable, at its best single cut ===");
        System.out.println("(popped pins should sit ABOVE the cut; scored over the whole corpus)\n");
        int best = -1;
        int fewestWrong = Integer.MAX_VALUE;
        for (int c = 0; c < NAMES.length; c++) {
            Cut cut = bestCut(byFeature.get(c));
            System.out.printf(Locale.ROOT, "  %-22s cut %+.3f  misreads %3d/%d (%.2f%%)%n",
                    NAMES[c], cut.at, cut.wrong, byFeature.get(c).size(),
                    100.0 * cut.wrong / byFeature.get(c).size());
            if (cut.wrong < fewestWrong) {
                fewestWrong = cut.wrong;
                best = c;
            }
        }

        System.out.printf(Locale.ROOT, "%n=== Best single: %s ===%n", NAMES[best]);
        Cut cut = bestCut(byFeature.get(best));
        for (Sample x : byFeature.get(best)) {
            boolean saysPopped = x.v >= cut.at;
            if (saysPopped != x.popped) {
                System.out.printf(Locale.ROOT, "  WRONG %5s  %+.3f  %s%n",
                        x.popped ? "POP" : "flat", x.v, x.where);
            }
        }

        // Does adding an orthogonal second feature to the best one clean up the tails?
        System.out.println("\n=== Best PAIR (feature A above its cut AND feature B above its cut) ===");
        int bestA = -1, bestB = -1, pairWrong = Integer.MAX_VALUE;
        double bestCa = 0, bestCb = 0;
        for (int a = 0; a < NAMES.length; a++) {
            for (int b = 0; b < NAMES.length; b++) {
                if (a == b) {
                    continue;
                }
                double[] result = bestPair(byFeature.get(a), byFeature.get(b));
                if ((int) result[0] < pairWrong) {
                    pairWrong = (int) result[0];
                    bestCa = result[1];
                    bestCb = result[2];
                    bestA = a;
                    bestB = b;
                }
            }
        }
        System.out.printf(Locale.ROOT, "  %s >= %+.3f  AND  %s >= %+.3f   misreads %d/%d (%.2f%%)%n",
                NAMES[bestA], bestCa, NAMES[bestB], bestCb, pairWrong, byFeature.get(0).size(),
                100.0 * pairWrong / byFeature.get(0).size());
        System.out.println("  the pins it still gets wrong:");
        List<Sample> fa = byFeature.get(bestA);
        List<Sample> fb = byFeature.get(bestB);
        for (int i = 0; i < fa.size(); i++) {
            boolean says = fa.get(i).v >= bestCa && fb.get(i).v >= bestCb;
            if (says != fa.get(i).popped) {
                System.out.printf(Locale.ROOT, "    WRONG %5s  a %+.3f b %+.3f  %s%n",
                        fa.get(i).popped ? "POP" : "flat", fa.get(i).v, fb.get(i).v, fa.get(i).where);
            }
        }
    }

    record Cut(double at, int wrong) {}

    static Cut bestCut(List<Sample> xs) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Sample x : xs) {
            lo = Math.min(lo, x.v);
            hi = Math.max(hi, x.v);
        }
        double bestAt = lo;
        int fewest = Integer.MAX_VALUE;
        for (double cut = lo; cut <= hi; cut += (hi - lo) / 400.0) {
            int wrong = 0;
            for (Sample x : xs) {
                if ((x.v >= cut) != x.popped) {
                    wrong++;
                }
            }
            if (wrong < fewest) {
                fewest = wrong;
                bestAt = cut;
            }
        }
        return new Cut(bestAt, fewest);
    }

    /** {wrong, cutA, cutB} for the best "A >= ca AND B >= cb" popped-classifier. */
    static double[] bestPair(List<Sample> a, List<Sample> b) {
        double loA = Double.MAX_VALUE, hiA = -Double.MAX_VALUE, loB = Double.MAX_VALUE, hiB = -Double.MAX_VALUE;
        for (int i = 0; i < a.size(); i++) {
            loA = Math.min(loA, a.get(i).v);
            hiA = Math.max(hiA, a.get(i).v);
            loB = Math.min(loB, b.get(i).v);
            hiB = Math.max(hiB, b.get(i).v);
        }
        int fewest = Integer.MAX_VALUE;
        double bca = loA, bcb = loB;
        for (double ca = loA; ca <= hiA; ca += (hiA - loA) / 40.0) {
            for (double cb = loB; cb <= hiB; cb += (hiB - loB) / 40.0) {
                int wrong = 0;
                for (int i = 0; i < a.size(); i++) {
                    boolean pop = a.get(i).popped;
                    boolean says = a.get(i).v >= ca && b.get(i).v >= cb;
                    if (says != pop) {
                        wrong++;
                    }
                }
                if (wrong < fewest) {
                    fewest = wrong;
                    bca = ca;
                    bcb = cb;
                }
            }
        }
        return new double[] {fewest, bca, bcb};
    }

    // --- corpus (same labels as ReaderBench) -----------------------------------------------------

    record Shot(String path, int[] offsets) {
        int n() {
            return offsets.length;
        }
    }

    static List<Shot> corpus() {
        List<Shot> out = new ArrayList<>();
        out.add(new Shot(FRAMES + "5p-plates-1-2-opposed/step-0.png", new int[] {0, 3, -2, 3, 3}));
        out.add(new Shot(FRAMES + "6p-gap-shadow/step-0.png", new int[] {2, 1, 1, -2, 3, -3}));
        for (int k = 0; k < 7; k++) {
            out.add(new Shot(FRAMES + "gamma/g-1.2/step-" + k + ".png", chest(k)));
            out.add(new Shot(FRAMES + "gamma/g-3.2/step-" + k + ".png", chest(k)));
            out.add(new Shot(FRAMES + "2560x1440/gamma-1.2-sweep/step-" + k + ".png", chest(k)));
        }
        for (String g : new String[] {"1.5", "1.8", "2.1", "2.4", "2.7", "3.0"}) {
            out.add(new Shot(FRAMES + "gamma/g-" + g + ".png", chest(0)));
        }
        for (String mode : SWEEP_MODES) {
            for (int k = 0; k < 7; k++) {
                out.add(new Shot(FRAMES + mode + "/front-plate-sweep/step-" + k + ".png",
                        new int[] {3, 1, 2, 0, 3 - k}));
            }
        }
        return out;
    }

    static final String[] SWEEP_MODES = {
        "3840x2160", "2560x1600", "2560x1440", "2048x1536", "1920x1440", "1920x1200",
        "1920x1080", "1680x1050", "1600x1200", "1600x1024", "1600x900", "1440x1080",
        "1366x768", "1360x768", "1280x1024", "1280x960", "1280x800", "1280x768",
        "1280x720", "1176x664", "1152x864", "1024x768", "800x600",
    };

    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }

    static BufferedImage read(String path) throws Exception {
        File f = new File(path);
        return f.isFile() ? ImageIO.read(f) : null;
    }
}
