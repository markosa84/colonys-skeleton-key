package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link LatticeReader}, the tone-free reader that is now the default. It reads
 * the lock from the lock's <b>own contrast</b> rather than from absolute pixel values, and it matches
 * the pixel-calibrated {@link LockReader} on <b>every</b> labelled frame in {@code src/test/data/frames/}
 * - the 53-frame 4K census, the whole gamma slider (1.2..3.2), and the 161-frame resolution sweep across
 * all 23 dev-machine display modes (800x600..4K) - while also reading HDR frames the calibrated reader
 * returns nothing on (demonstrated in {@code tools/ReaderBench}; the corpus has no labelled HDR frame,
 * so the dark end of the gamma slider is the darkest in-corpus proxy here).
 *
 * <p>Two kinds of assertion:
 * <ul>
 *   <li><b>the reads</b>, exactly: plate count and every offset, on the whole corpus;</li>
 *   <li><b>the safety properties</b> a session leans on, also on the whole corpus: never a
 *       <b>wrong</b> plate count (a wrong smaller count is the bug that cost a player picks), never a
 *       <b>false pop</b> (says centred when it is not, which could call a lock open that is not), and
 *       every offset in range or {@link LockModel#UNKNOWN}.</li>
 * </ul>
 *
 * <p>No game and no display are needed: the reader is pure frame analysis, so everything here reads
 * PNGs headless.
 */
class LatticeReaderTest {

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is missing"
                        + " from this checkout - the reader's entire validation lives there");
    }

    // -- the reads, exactly ------------------------------------------------------------------------

    /** The 4K census: five slide sequences (5-, 6- and 7-plate), each a chain of single steps. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("censusFrames")
    void readsEveryCensusFrame(String frame, int[] expected) {
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        BufferedImage img = TestFrames.load(frame);
        assertEquals(expected.length, reader.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, expected.length), frame + ": offsets");
    }

    /**
     * The whole gamma slider, end to end. Read through the {@link Tone} each frame carries, every
     * setting from 1.2 to 3.2 gives the calibrated labels - the hybrid maps the dark end back to the
     * calibrated look and it then reads as easily as a calibrated frame.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gammaFrames")
    void readsTheWholeGammaSlider(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LatticeReader reader = new LatticeReader(viewport, Tone.estimate(img, viewport));
        assertEquals(expected.length, reader.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, expected.length), frame + ": offsets");
    }

    /** The front-plate sweep at every one of the 23 display modes, 800x600 through 4K. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("sweepFrames")
    void readsTheFrontPlateSweepAtEveryResolution(String frame, Viewport viewport, int[] expected) {
        LatticeReader reader = new LatticeReader(viewport);
        BufferedImage img = TestFrames.load(frame);
        assertEquals(expected.length, reader.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, expected.length), frame + ": offsets");
    }

    // -- the safety properties, over the whole corpus ----------------------------------------------

    /**
     * <b>Never a wrong plate count.</b> The one answer a reader must never give is a plausible-but-wrong
     * count: it hands the session a model with the wrong number of plates to drive into walls. The right
     * count or -1 are both safe; a different 4..7 is not.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyLabelledFrame")
    void neverAnswersAWrongPlateCount(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        int n = new LatticeReader(viewport, Tone.estimate(img, viewport)).detectPlateCount(img);
        assertTrue(n == expected.length || n == -1,
                frame + ": plate count was " + n + ", which is neither the truth (" + expected.length
                        + ") nor a refusal (-1)");
    }

    /**
     * <b>Never a false pop.</b> {@code readCentered} is the only signal a lock may be declared open
     * from, so the error it must not make is saying a plate is centred when it is not. A missed pop -
     * a real one read too faint - only costs a re-read, and does happen at small resolutions; a false
     * pop could call a lock open that is not, and must never happen. It doesn't, on any frame.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyLabelledFrame")
    void neverFalsePops(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        boolean[] centred = new LatticeReader(viewport, Tone.estimate(img, viewport))
                .readCentered(img, expected.length);
        for (int i = 0; i < expected.length; i++) {
            if (centred[i]) {
                assertEquals(0, expected[i],
                        frame + ": plate " + i + " read as popped, but its offset is " + expected[i]);
            }
        }
    }

    /** Every offset it returns is a real one - in [-3, +3], or UNKNOWN, never anything else. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyLabelledFrame")
    void everyOffsetIsInRangeOrUnknown(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LatticeReader reader = new LatticeReader(viewport, Tone.estimate(img, viewport));
        int n = reader.detectPlateCount(img);
        if (n < LockModel.MIN_PLATES) {
            return;
        }
        for (int v : reader.readState(img, n)) {
            assertTrue(v == LockModel.UNKNOWN || (v >= -LockModel.MAX_OFFSET && v <= LockModel.MAX_OFFSET),
                    frame + ": offset " + v + " is out of range");
        }
    }

    // -- it counts holes, not pins -----------------------------------------------------------------

    /**
     * The reader counts <b>hole rows</b>, not pins - which is the whole reason it cannot make the bug
     * that cost a player picks. Paint out a pin (the faintest thing, and what a dark or HDR frame takes)
     * and the row is still there, so the plate count is unchanged. {@link LockReader}, which counts
     * pins, would answer the smaller lock its remaining pins happen to cover; this one does not move.
     */
    @Test
    void aPaintedOutPinDoesNotChangeThePlateCount() {
        BufferedImage img = TestFrames.load("6p-plate-5-sweep/step-0.png");
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        assertEquals(6, reader.detectPlateCount(img));

        // The front pin's fan position at 6 plates, from the shared geometry, painted to black.
        double[] frontPin = new FanGeometry(Viewport.REFERENCE).pinPosition(6, 5);
        blackOut(img, (int) frontPin[0], (int) frontPin[1], 24);

        assertEquals(6, reader.detectPlateCount(img),
                "a plate is a row of holes; blacking out its pin leaves the row, so it is still 6");
    }

    /** Paints a square of the frame black. */
    private static void blackOut(BufferedImage img, int cx, int cy, int radius) {
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                if (x >= 0 && y >= 0 && x < img.getWidth() && y < img.getHeight()) {
                    img.setRGB(x, y, 0);
                }
            }
        }
    }

    // -- the relative pipeline, without any tone ---------------------------------------------------

    /**
     * The no-tone constructor is the pure relative pipeline, and it reads the calibrated frames on its
     * own - which is what lets the hybrid fall back to it on an HDR frame, where {@link Tone} would only
     * do harm. (At the calibrated gamma the tone is the identity anyway, so this also shows the two
     * constructors agree there.)
     */
    @Test
    void theRelativePipelineReadsACensusFrameWithNoTone() {
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        assertEquals(6, reader.detectPlateCount(img));
        assertArrayEquals(new int[] {2, 1, 1, -2, 3, -3}, reader.readState(img, 6));
    }

    /**
     * When the tone could not be trusted - a guess (no panel found) or an off-family HDR frame - the
     * reader must NOT map through it (the curve would be worse than nothing) and must read the frame
     * raw. {@link Tone#UNREADABLE} is a guess, so it exercises exactly that fallback branch, and a
     * calibrated frame still reads through it.
     */
    @Test
    void ignoresAToneItCannotTrustAndReadsTheFrameRaw() {
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE, Tone.UNREADABLE);
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        assertEquals(6, reader.detectPlateCount(img));
        assertArrayEquals(new int[] {2, 1, 1, -2, 3, -3}, reader.readState(img, 6));
    }

    // -- degenerate input --------------------------------------------------------------------------

    @Test
    void refusesABlankFrame() {
        BufferedImage blank = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        assertEquals(-1, reader.detectPlateCount(blank));
        assertFalse(reader.describe(blank).isEmpty());
    }

    @Test
    void describeReportsThePlateCountItFound() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        String described = new LatticeReader(Viewport.REFERENCE).describe(img);
        assertTrue(described.contains("6 plates"), described);
        assertNotEquals("", described);
    }

    /**
     * The calibration surface {@code tools/ReaderBench} and {@code tools/PopProbe2} run against: one
     * {@link LatticeReader.RowFit} per plate, and the two pop readings that back it. Exercised here so
     * it stays working, and so the pop's own two-gate rule is checked against the record it exposes.
     */
    @Test
    void exposesPerRowFitsAndPopFeaturesForCalibration() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);

        List<LatticeReader.RowFit> fits = reader.rows(img, 6);
        List<double[]> features = reader.popFeatures(img, 6);
        assertEquals(6, fits.size());
        assertEquals(6, features.size());
        for (int i = 0; i < 6; i++) {
            LatticeReader.RowFit f = fits.get(i);
            assertEquals(f.pinDark(), features.get(i)[0], 1e-9, "pinDark must match the fit");
            assertEquals(f.discDark(), features.get(i)[1], 1e-9, "discDark must match the fit");
            assertTrue(f.isPlate(), "6p-gap-shadow is six clean plates");
        }
    }

    // -- frame sets --------------------------------------------------------------------------------

    /** The 4K census - the same labels {@code LockReaderTest} pins, from the same slide sequences. */
    static Stream<Arguments> censusFrames() {
        List<Arguments> frames = new ArrayList<>();
        frames.add(census("5p-plates-1-2-opposed/step-0.png", 0, 3, -2, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-1.png", 0, 2, -1, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-2.png", 0, 1, 0, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-3.png", 0, 0, 1, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-4.png", 0, -1, 2, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-5.png", 0, -2, 3, 3, 3));
        for (int k = 0; k < 7; k++) {
            frames.add(census("6p-plate-5-sweep/step-" + k + ".png", 3, 0, 1, 2, -3, k - 3));
        }
        for (int k = 0; k < 4; k++) {
            frames.add(census("6p-plate-0-drags-1/step-" + k + ".png", 3 - k, -k, 1, 2, -3, -2));
        }
        for (int k = 0; k < 6; k++) {
            frames.add(census("6p-plates-0-and-5/step-0" + k + ".png", 3 - k, -1, 0, 2 - k, 3, -2));
        }
        frames.add(census("6p-plates-0-and-5/step-06.png", -3, -1, -1, -3, 3, -2));
        for (int k = 0; k < 7; k++) {
            frames.add(census("6p-plates-0-and-5/step-" + String.format("%02d", 7 + k) + ".png",
                    -3, -1, -1, -3, 3, k - 3));
        }
        frames.add(census("6p-gap-shadow/step-0.png", 2, 1, 1, -2, 3, -3));
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-2-sweep/step-" + k + ".png", 0, -2, k - 3, 3, 3, 2, 3));
        }
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-6-drags-1/step-" + k + ".png", 0, 3 - k, -3, 3, 3, 2, 3 - k));
        }
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-0-drags-4/step-" + k + ".png", 3 - k, 0, -3, 0, 3 - k, 2, 3));
        }
        return frames.stream();
    }

    private static Arguments census(String path, int... expected) {
        return Arguments.of(path, expected);
    }

    static Stream<Arguments> gammaFrames() {
        List<Arguments> frames = new ArrayList<>();
        for (String gamma : new String[] {"1.2", "3.2"}) {
            for (int k = 0; k <= 6; k++) {
                frames.add(Arguments.of("gamma/g-" + gamma + "/step-" + k + ".png",
                        Viewport.REFERENCE, chest(k)));
            }
        }
        for (int k = 0; k <= 6; k++) {
            frames.add(Arguments.of("2560x1440/gamma-1.2-sweep/step-" + k + ".png",
                    new Viewport(2560, 1440), chest(k)));
        }
        for (String gamma : new String[] {"1.5", "1.8", "2.1", "2.4", "2.7", "3.0"}) {
            frames.add(Arguments.of("gamma/g-" + gamma + ".png", Viewport.REFERENCE, chest(0)));
        }
        return frames.stream();
    }

    static final String[] SWEEP_MODES = {
            "3840x2160", "2560x1600", "2560x1440", "2048x1536", "1920x1440", "1920x1200",
            "1920x1080", "1680x1050", "1600x1200", "1600x1024", "1600x900", "1440x1080",
            "1366x768", "1360x768", "1280x1024", "1280x960", "1280x800", "1280x768",
            "1280x720", "1176x664", "1152x864", "1024x768", "800x600",
    };

    static Stream<Arguments> sweepFrames() {
        List<Arguments> frames = new ArrayList<>();
        for (String mode : SWEEP_MODES) {
            String[] wh = mode.split("x");
            Viewport viewport = new Viewport(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            for (int k = 0; k <= 6; k++) {
                frames.add(Arguments.of(mode + "/front-plate-sweep/step-" + k + ".png",
                        viewport, new int[] {3, 1, 2, 0, 3 - k}));
            }
        }
        return frames.stream();
    }

    /** Every labelled frame with its viewport - for the whole-corpus safety properties. */
    static Stream<Arguments> everyLabelledFrame() {
        List<Arguments> frames = new ArrayList<>();
        censusFrames().forEach(a -> frames.add(Arguments.of(a.get()[0], Viewport.REFERENCE, a.get()[1])));
        gammaFrames().forEach(frames::add);
        sweepFrames().forEach(frames::add);
        return frames.stream();
    }

    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }
}
