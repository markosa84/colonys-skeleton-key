import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.vision.LatticeReader;
import io.github.markosa84.colonysskeletonkey.vision.LockReader;
import io.github.markosa84.colonysskeletonkey.vision.Tone;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;

/**
 * Scores {@link LockReader} and {@link LatticeReader} against each other, over every labelled frame in
 * the corpus and over the HDR dumps that defeat the first one.
 *
 * <pre>
 *   java --source-path src/main/java tools/ReaderBench.java
 * </pre>
 *
 * <p>The answer, in numbers, and it is why {@link LatticeReader} is now the default:
 *
 * <ul>
 *   <li><b>On home ground it is equal.</b> On the labelled corpus - which the old reader is calibrated
 *       to the pixel on - the lattice reader matches it frame for frame: 190/190 plate counts,
 *       1005/1005 offsets.</li>
 *   <li><b>And it wins where the old one loses.</b> On the HDR dumps, where the old reader returns -1
 *       and the player is told "no lock detected", the lattice reader finds the lock.</li>
 * </ul>
 *
 * <p>It also reports the <b>pop</b> as the reader actually decides it - two gates, {@code readCentered}
 * - counting false pops (says centred, is not) apart from missed pops (a real pop read faint). The
 * first is the error a session cannot afford; the second only costs a re-read. {@code tools/PopProbe2}
 * is where those two gates were fitted.
 */
public final class ReaderBench {

    static final String FRAMES = "src/test/data/frames/";

    record Shot(String path, int[] offsets) {

        int n() {
            return offsets.length;
        }
    }

    public static void main(String[] args) throws Exception {
        List<Shot> corpus = corpus();

        System.out.println("=== The pop, as the reader actually decides it (two gates) ===");
        int falsePop = 0, missedPop = 0, pins = 0;
        for (Shot s : corpus) {
            BufferedImage img = read(s.path());
            if (img == null) {
                continue;
            }
            Viewport vp0 = new Viewport(img.getWidth(), img.getHeight());
            boolean[] centred = new LatticeReader(vp0, Tone.estimate(img, vp0)).readCentered(img, s.n());
            for (int i = 0; i < s.n(); i++) {
                pins++;
                boolean truth = s.offsets()[i] == 0;
                if (centred[i] && !truth) {
                    falsePop++;   // says centred when it is not - the error the session cannot afford
                } else if (!centred[i] && truth) {
                    missedPop++;  // misses a real pop - only costs a re-read
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "  %d pins: %d false pops (says centred, is not), %d missed pops (real, read faint)"
                + " -> %.2f%% wrong, and %s%n%n",
                pins, falsePop, missedPop, 100.0 * (falsePop + missedPop) / pins,
                falsePop == 0 ? "not one of them the dangerous kind" : "SOME ARE FALSE POPS");

        System.out.println("=== Reader vs reader, on the labelled corpus ===\n");
        System.out.printf(Locale.ROOT, "%-28s %-7s %-14s %-14s %-10s%n",
                "corpus", "frames", "plate count", "offsets", "ms/frame");
        for (String group : new String[] {
                "4K census", "gamma slider", "resolution sweep", "7-plate"}) {
            score(group, corpus.stream().filter(s -> group(s.path()).equals(group)).toList());
        }
        score("ALL", corpus);

        System.out.println("\n=== The HDR dumps: what the two readers make of a frame that is not");
        System.out.println("    darker than the gamma slider can make it, but darker than it can EXPLAIN ===\n");
        for (String hdr : hdrDumps()) {
            BufferedImage img = read(hdr);
            if (img == null) {
                System.out.println("  # missing " + hdr);
                continue;
            }
            Viewport vp = new Viewport(img.getWidth(), img.getHeight());
            int oldN = new LockReader(vp, Tone.estimate(img, vp)).detectPlateCount(img);
            LatticeReader latt = new LatticeReader(vp, Tone.estimate(img, vp));
            int newN = latt.detectPlateCount(img);
            System.out.printf(Locale.ROOT, "  %-42s %dx%d%n", name(hdr), img.getWidth(), img.getHeight());
            System.out.printf(Locale.ROOT, "      LockReader     -> %s%n",
                    oldN < 0 ? "NO LOCK DETECTED" : oldN + " plates " + Arrays.toString(
                            new LockReader(vp, Tone.estimate(img, vp)).readState(img, oldN)));
            System.out.printf(Locale.ROOT, "      LatticeReader  -> %s%n", newN < 0
                    ? "no lock"
                    : newN + " plates " + offsets(latt.readState(img, newN))
                            + "   centred " + Arrays.toString(latt.readCentered(img, newN)));
        }
    }

    /** Plate count and offset accuracy for both readers over one group of frames. */
    static void score(String label, List<Shot> shots) throws Exception {
        int oldPlates = 0, newPlates = 0, oldOffsets = 0, newOffsets = 0;
        int oldUnknown = 0, newUnknown = 0, total = 0, frames = 0;
        long oldNanos = 0, newNanos = 0;
        for (Shot s : shots) {
            BufferedImage img = read(s.path());
            if (img == null) {
                continue;
            }
            frames++;
            Viewport vp = new Viewport(img.getWidth(), img.getHeight());
            LockReader old = new LockReader(vp, Tone.estimate(img, vp));
            LatticeReader latt = new LatticeReader(vp, Tone.estimate(img, vp));

            long t0 = System.nanoTime();
            int on = old.detectPlateCount(img);
            int[] os = on == s.n() ? old.readState(img, on) : null;
            oldNanos += System.nanoTime() - t0;

            long t1 = System.nanoTime();
            int nn = latt.detectPlateCount(img);
            int[] ns = nn == s.n() ? latt.readState(img, nn) : null;
            newNanos += System.nanoTime() - t1;

            if (on == s.n()) {
                oldPlates++;
            }
            if (nn == s.n()) {
                newPlates++;
            }
            for (int i = 0; i < s.n(); i++) {
                total++;
                if (os != null && os[i] == s.offsets()[i]) {
                    oldOffsets++;
                }
                if (os != null && os[i] == LockModel.UNKNOWN) {
                    oldUnknown++;
                }
                if (ns != null && ns[i] == s.offsets()[i]) {
                    newOffsets++;
                }
                if (ns != null && ns[i] == LockModel.UNKNOWN) {
                    newUnknown++;
                }
            }
        }
        if (frames == 0) {
            return;
        }
        System.out.printf(Locale.ROOT, "%-28s %-7d LockReader     %3d/%-3d       %4d/%-5d   %.1f%n",
                label, frames, oldPlates, frames, oldOffsets, total, oldNanos / 1e6 / frames);
        System.out.printf(Locale.ROOT, "%-28s %-7s LatticeReader  %3d/%-3d       %4d/%-5d   %.1f%n",
                "", "", newPlates, frames, newOffsets, total, newNanos / 1e6 / frames);
        if (oldUnknown + newUnknown > 0) {
            System.out.printf(Locale.ROOT, "%-28s %-7s (unknown rows: old %d, new %d)%n",
                    "", "", oldUnknown, newUnknown);
        }
    }

    static String group(String path) {
        if (path.contains("/gamma")) {
            return "gamma slider";
        }
        if (path.contains("front-plate-sweep")) {
            return "resolution sweep";
        }
        if (path.contains("/7p-")) {
            return "7-plate";
        }
        return "4K census";
    }

    /** The labels, the same arithmetic {@code LockReaderTest} generates them from. */
    static List<Shot> corpus() {
        List<Shot> out = new ArrayList<>();

        // The 4K census: four slide sequences, each a chain of single steps.
        out.add(new Shot(FRAMES + "5p-plates-1-2-opposed/step-0.png", new int[] {0, 3, -2, 3, 3}));
        out.add(new Shot(FRAMES + "6p-gap-shadow/step-0.png", new int[] {2, 1, 1, -2, 3, -3}));
        out.add(new Shot(FRAMES + "plate-count/4-plates.png", null));
        out.add(new Shot(FRAMES + "plate-count/5-plates.png", null));
        out.add(new Shot(FRAMES + "plate-count/6-plates.png", null));
        out.removeIf(s -> s.offsets() == null); // plate-count frames carry no offset labels

        // The gamma slider: the 7-plate chest, plate 2 sweeping its own track.
        for (int k = 0; k < 7; k++) {
            out.add(new Shot(FRAMES + "gamma/g-1.2/step-" + k + ".png", chest(k)));
            out.add(new Shot(FRAMES + "gamma/g-3.2/step-" + k + ".png", chest(k)));
            out.add(new Shot(FRAMES + "2560x1440/gamma-1.2-sweep/step-" + k + ".png", chest(k)));
        }
        for (String g : new String[] {"1.5", "1.8", "2.1", "2.4", "2.7", "3.0"}) {
            out.add(new Shot(FRAMES + "gamma/g-" + g + ".png", chest(0)));
        }

        // The resolution sweep: one 5-plate lock, all 19 display modes, front plate swept.
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
        "1280x720",
    };

    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }

    static List<String> hdrDumps() {
        List<String> out = new ArrayList<>();
        for (String dir : new String[] {"captures/1", "captures/2"}) {
            File[] files = new File(dir).listFiles((d, f) -> f.startsWith("no-lock") && f.endsWith(".png"));
            if (files != null) {
                Arrays.sort(files);
                for (File f : files) {
                    out.add(f.getPath().replace('\\', '/'));
                }
            }
        }
        return out;
    }

    static BufferedImage read(String path) throws Exception {
        File f = new File(path);
        return f.isFile() ? ImageIO.read(f) : null;
    }

    static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    static String offsets(int[] state) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < state.length; i++) {
            b.append(i > 0 ? ", " : "")
                    .append(state[i] == LockModel.UNKNOWN ? "?" : String.valueOf(state[i]));
        }
        return b.append(']').toString();
    }

    static double q(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int i = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }
}
