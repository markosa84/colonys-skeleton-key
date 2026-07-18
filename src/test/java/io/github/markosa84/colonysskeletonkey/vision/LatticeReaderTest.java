package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
 * all 23 dev-machine display modes (800x600..4K) - and additionally reads the labelled <b>HDR</b> corpus
 * ({@code hdr/}), where the calibrated reader returns nothing: an HDR tonemap is off the gamma family,
 * so {@link LockReader} refuses (-1) while this one reads every frame from the lock's own contrast.
 *
 * <p>This is where <b>the reads</b> are pinned, exactly: plate count and every offset, over the whole
 * corpus. The safety properties every reader owes - never a wrong plate count, never a false pop,
 * every offset in range or UNKNOWN - are not this reader's business alone and live in
 * {@link AnalyzerContractTest}, which asks them of both.
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

    /**
     * The labelled HDR corpus - the same 7-plate states as the gamma slider, captured with the game's
     * HDR mode on. An HDR tonemap is <b>off</b> the gamma family (its panel white sits far below what
     * its ink says), so the {@link Tone} the frame carries is not trusted and the reader reads raw,
     * from the lock's own contrast - and reads every state correctly, where the calibrated
     * {@link LockReader} refuses (see {@code HdrCorpusTest}). This is the failure mode three players
     * reported; it now has fixtures.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hdrFrames")
    void readsTheHdrCorpus(String frame, Viewport viewport, int[] expected) {
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

    // -- frame sets: the labels live in FrameCorpus, and nowhere else ------------------------------

    static Stream<Arguments> censusFrames() {
        return FrameCorpus.censusFrames();
    }

    static Stream<Arguments> gammaFrames() {
        return FrameCorpus.gammaFrames();
    }

    static Stream<Arguments> hdrFrames() {
        return FrameCorpus.hdrFrames();
    }

    static Stream<Arguments> sweepFrames() {
        return FrameCorpus.sweepFrames();
    }
}
