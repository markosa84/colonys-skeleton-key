import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;

import io.github.markosa84.colonysskeletonkey.vision.FanLocator;
import io.github.markosa84.colonysskeletonkey.vision.LatticeReader;
import io.github.markosa84.colonysskeletonkey.vision.Tone;
import io.github.markosa84.colonysskeletonkey.vision.ViewMapping;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;

/**
 * Scores {@link FanLocator} against the labelled corpus and the reporters' HDR dumps: how often it
 * finds the lock, how close the pose is to the truth, and what it costs.
 *
 * <p>For a fixture the truth is known - the frame IS the game's view, so the right pose is
 * {@code new Viewport(width, height).mapping()}. The interesting numbers are therefore the centre
 * error (in hole spacings, because that is the unit the reader's walk cares about) and the scale
 * error.
 *
 * <p><b>The number that must be zero is the false-accept count</b>, and it is measured on frames
 * with no lock in them at all. A locator that finds locks in the furniture is worse than none: the
 * whole point is to be trusted on the one frame a player's tool has already failed to read.
 *
 * <pre>
 *   javac -cp build/classes/java/main -d build/tools tools/LocatorBench.java
 *   java -cp "build/classes/java/main;build/tools" LocatorBench
 * </pre>
 */
public final class LocatorBench {

    private static final File FRAMES = new File(System.getProperty(
            "lockpick.frames.dir", "src/test/data/frames"));

    public static void main(String[] args) throws Exception {
        List<String> groups = args.length > 0 ? Arrays.asList(args) : List.of(
                "3840x2160/front-plate-sweep", "2560x1440/front-plate-sweep",
                "1920x1080/front-plate-sweep", "1280x720/front-plate-sweep",
                "1024x768/front-plate-sweep", "800x600/front-plate-sweep",
                "gamma/g-1.2", "gamma/g-3.2",
                "6p-gap-shadow", "7p-plate-2-sweep", "5p-plates-1-2-opposed");

        System.out.printf("%-34s %5s %6s %8s %9s %8s %7s%n",
                "group", "found", "of", "centre", "centre/u", "scale", "ms");
        Stats all = new Stats();
        for (String group : groups) {
            Stats s = run(new File(FRAMES, group), group);
            all.add(s);
        }
        System.out.println();
        System.out.printf("TOTAL: located %d of %d, worst centre %.2f hole spacings, "
                        + "worst scale error %.2f%%, %.0f ms/frame%n",
                all.found, all.total, all.worstCentreU, 100 * all.worstScale, all.meanMs());
        System.out.printf("       MISREADS: %d - a located pose that reads the lock differently "
                        + "from the true one. This is the number that must be zero.%n", all.wrong);
    }

    static Stats run(File dir, String label) throws Exception {
        File[] pngs = dir.listFiles(f -> f.getName().endsWith(".png"));
        Stats s = new Stats();
        if (pngs == null) {
            System.out.printf("%-34s  (missing)%n", label);
            return s;
        }
        Arrays.sort(pngs);
        FanLocator locator = new FanLocator();
        for (File png : pngs) {
            BufferedImage img = ImageIO.read(png);
            ViewMapping truth = new Viewport(img.getWidth(), img.getHeight()).mapping();
            long t0 = System.nanoTime();
            FanLocator.Fit fit = locator.locate(img);
            s.ms.add((System.nanoTime() - t0) / 1e6);
            s.total++;
            if (!fit.found()) {
                continue;
            }
            s.found++;
            double[] want = centre(truth);
            double[] got = centre(fit.mapping());
            double err = Math.hypot(want[0] - got[0], want[1] - got[1]);
            double spacing = 48 * truth.scale();
            s.worstCentre = Math.max(s.worstCentre, err);
            s.worstCentreU = Math.max(s.worstCentreU, err / spacing);
            s.worstScale = Math.max(s.worstScale,
                    Math.abs(fit.mapping().scale() - truth.scale()) / truth.scale());

            // The only question that matters. A pose is not "found" because the reader read
            // SOMETHING at it - a lattice is periodic, so a pose a slot out reads the lock and
            // reports wrong offsets without complaint. So: read it at the located pose, read it at
            // the pose the corpus says is true, and demand they agree. Anything else is a lie.
            String here = read(img, fit.mapping());
            String there = read(img, truth);
            if (!here.equals(there)) {
                s.wrong++;
                System.out.printf(Locale.ROOT, "    !! %-38s located %s, truth %s%n",
                        png.getParentFile().getName() + "/" + png.getName(), here, there);
            }
        }
        System.out.printf(Locale.ROOT, "%-34s %5d %6d %8.1f %9.3f %7.2f%% %7.0f  %s%n",
                label, s.found, s.total, s.worstCentre, s.worstCentreU, 100 * s.worstScale,
                s.meanMs(), s.wrong == 0 ? "" : "MISREAD " + s.wrong);
        return s;
    }

    /** Where a pose puts the fan centre, in the frame's own pixels. */
    static double[] centre(ViewMapping m) {
        return new double[] {m.x(3090.0), m.y(798.0)};
    }

    /** What the reader makes of the frame at a pose: the plate count and every offset. */
    static String read(BufferedImage img, ViewMapping pose) {
        LatticeReader reader = new LatticeReader(pose, Tone.estimate(img, pose));
        int n = reader.detectPlateCount(img);
        return n < 4 ? "none" : n + Arrays.toString(reader.readState(img, n));
    }

    static final class Stats {
        int found, total, wrong;
        double worstCentre, worstCentreU, worstScale;
        final List<Double> ms = new ArrayList<>();

        void add(Stats o) {
            found += o.found;
            total += o.total;
            wrong += o.wrong;
            worstCentre = Math.max(worstCentre, o.worstCentre);
            worstCentreU = Math.max(worstCentreU, o.worstCentreU);
            worstScale = Math.max(worstScale, o.worstScale);
            ms.addAll(o.ms);
        }

        double meanMs() {
            return ms.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
    }
}
