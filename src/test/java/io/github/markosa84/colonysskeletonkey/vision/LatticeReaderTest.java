package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
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
 * - the 53-frame 4K census, the whole gamma slider (1.2..3.2), and the 133-frame resolution sweep across
 * all 19 dev-machine display modes (1280x720..4K) - and additionally reads the labelled <b>HDR</b> corpus
 * ({@code hdr/}), where the calibrated reader returns nothing: an HDR tonemap is off the gamma family,
 * so {@link LockReader} refuses (-1) while this one reads every frame from the lock's own contrast.
 *
 * <p>This is where <b>the reads</b> are pinned, exactly: plate count and every offset, over the whole
 * corpus. The safety properties every reader owes - never a wrong plate count, every offset in range
 * or UNKNOWN - are not this reader's business alone and live in
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

    /**
     * The four dark 2560x1440 6-plate reports from {@code captures/4}: a player at 1440p with the
     * in-game brightness turned down. On v1.3.0 these produced {@code unsolvable-model} give-ups,
     * because a misread while probing corrupted a connection. The reader reads all four correctly now
     * - including three near-centred plates the old pin-pop reader FALSE-POPPED to 0 that are really
     * +1 (verified plate by plate against the frames; see the group's {@code labels.txt}). Removing
     * pin-pop fixed that source; this holds the current reader to reading the dark 1440p regime right,
     * and the session's recovery from the wrong models it once built is pinned in {@code LockSessionTest}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("darkFrames")
    void readsTheDarkFrameReports(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LatticeReader reader = new LatticeReader(viewport, Tone.estimate(img, viewport));
        assertEquals(expected.length, reader.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, expected.length), frame + ": offsets");
    }

    /**
     * The 7-plate chest that was reported as "no lock detected" while every pixel of it was fine.
     *
     * <p>It is the frame that showed a plate's steel could not be measured as the median of the
     * sampling strip: the back plate sat at +3, a quarter of its strip lay past the end of the plate
     * on black room, and the median came back 162 where the steel reads 210-255. Three of that row's
     * six holes then traced too thin to survive the blob gates and the row walked 3/6, which cost the
     * whole lock. So this pins two things at once - {@code brightMedian} measuring the plate rather
     * than the strip, and {@code HOLE_DARK} re-referenced to it - and it must read all seven rows,
     * not merely find seven plates.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("stripOffPlateFrames")
    void readsTheChestWhoseStripRanOffThePlate(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LatticeReader reader = new LatticeReader(viewport, Tone.estimate(img, viewport));
        assertEquals(expected.length, reader.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, expected.length), frame + ": offsets");
        for (LatticeReader.RowFit row : reader.rows(img, expected.length)) {
            assertTrue(row.isPlate(), frame + ": every row resolves, not just enough of them");
        }
    }

    /**
     * The corpus's only labelled four-plate lock, and the count that matters most about it: <b>4, not
     * 6</b>. A four-plate fan is exactly the middle of a six-plate one, so the two extra rows a
     * six-plate answer needs are the ones at the ends - and on this chest both are occupied by
     * something that could pass for a plate at a glance. Behind the fan is the room (its whole strip
     * peaks at luminance 38 where the plates read 255); in front of it is the lock's dark casing, the
     * piece holding the keyhole and the pick, which sits one depth step ahead of the front plate at
     * every plate count and reads about 0.28 of the brightest plate. That casing is what
     * {@code LIT_PLATE} exists to reject, and this is the only frame where it has to do it at a
     * four-plate fan's front.
     *
     * <p>The frame is a live "wrong-model" dump, and the reader was never at fault on it: the run
     * failed because the offsets below also belong to a different four-plate chest in the catalogue.
     * See {@code 4p-dark-casing/labels.txt} and {@code LockRecallTest}.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fourPlateFrames")
    void readsTheFourPlateChestWithoutMistakingItsCasingForAPlate(
            String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LatticeReader reader = new LatticeReader(viewport, Tone.estimate(img, viewport));
        assertEquals(4, expected.length, "this group is the four-plate one");
        assertEquals(4, reader.detectPlateCount(img), frame + ": four plates, never six");
        assertArrayEquals(expected, reader.readState(img, 4), frame + ": offsets");
        for (LatticeReader.RowFit row : reader.rows(img, 4)) {
            assertTrue(row.isPlate(), frame + ": every row resolves");
        }
    }

    /** The front-plate sweep at every one of the 19 display modes, 1280x720 through 4K. */
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

    // -- a row that will not resolve costs that row, not the lock ----------------------------------

    /**
     * A fan may carry <b>one</b> row whose holes did not add up and still be a lock: that row reads
     * {@link LatticeReader#UNKNOWN}, which the session already knows how to recover, and the plate
     * count is unharmed because it is carried by the fan's <i>ends</i>, not by its middle.
     *
     * <p>This is what a live 7-plate chest cost before: six rows walked 6/6, the back one walked 3/6,
     * and the player was told there was no lock at all. See {@code 7p-strip-off-plate/labels.txt}.
     */
    @Test
    void oneUnresolvedRowStillLeavesALock() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        assertEquals(6, reader.detectPlateCount(img));

        fillHoleRow(img, 6, 2);

        assertEquals(6, reader.detectPlateCount(img), "one row short of its holes is still six plates");
        assertArrayEquals(new int[] {2, 1, LatticeReader.UNKNOWN, -2, 3, -3}, reader.readState(img, 6),
                "the row that would not resolve reads UNKNOWN; the others are untouched");
    }

    /**
     * Two, though, and the reader refuses. The tolerance is what keeps a single bad row from costing a
     * lock; it is not licence to answer from half a fan, and it is the bound that stops it reaching a
     * wrong-parity fan (whose rows land in the gaps between plates, six of them holeless at once).
     */
    @Test
    void twoUnresolvedRowsAreNotALock() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        fillHoleRow(img, 6, 2);
        fillHoleRow(img, 6, 3);

        assertEquals(-1, new LatticeReader(Viewport.REFERENCE).detectPlateCount(img));
    }

    /**
     * The beyond-check is <b>not</b> relaxed with the fan itself. It is the test that can only ever
     * take a lock away - it is how a 4- or 5-plate fan proves it is not the middle of a bigger lock -
     * so it still demands a whole six-hole row before it fires. Blank the holes of the row one step
     * past a genuine 5-plate lock and the answer must not change.
     */
    @Test
    void theBeyondCheckStillNeedsAWholeRow() {
        BufferedImage img = TestFrames.load("plate-count/5-plates.png");
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);
        assertEquals(5, reader.detectPlateCount(img));

        fillHoleRow(img, 5, -1);
        fillHoleRow(img, 5, 5);

        assertEquals(5, reader.detectPlateCount(img), "still five plates, and still not seven");
    }

    /**
     * Paints a plate's hole row over with its own steel: lit metal at the right depth, and not one
     * hole in it. The band is walked in <b>screen</b> space and tested after un-rotating, so no
     * destination pixel is missed - mapping a rotated grid forward leaves gaps, and a single stray
     * dark pixel here would be a hole the reader could find.
     */
    private static void fillHoleRow(BufferedImage img, int n, int row) {
        FanGeometry geo = new FanGeometry(Viewport.REFERENCE);
        double[] pin = geo.rowPinAtDepth((n - 1) / 2.0 - row);
        double slope = FanGeometry.slopeAtDepth((n - 1) / 2.0 - row);
        Rectangle bounds = geo.fanCropScreenBounds(LockModel.MAX_PLATES);
        List<int[]> band = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        for (int y = bounds.y; y < bounds.y + bounds.height; y++) {
            for (int x = bounds.x; x < bounds.x + bounds.width; x++) {
                if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) {
                    continue;
                }
                double[] r = geo.rotatePoint(x, y, FanGeometry.ROT_DEG);
                double lineY = pin[1] + (r[0] - pin[0]) * slope;
                if (Math.abs(r[1] - lineY) <= 20 && Math.abs(r[0] - pin[0]) <= geo.rowMaxDx) {
                    band.add(new int[] {x, y});
                    levels.add(Pixels.luminance(img.getRGB(x, y)));
                }
            }
        }
        // The plate's steel, not its brightest speck: a specular streak must not set the fill.
        Collections.sort(levels);
        int steel = levels.get((int) (levels.size() * 0.75));
        int grey = (steel << 16) | (steel << 8) | steel;
        for (int[] p : band) {
            img.setRGB(p[0], p[1], grey);
        }
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
     * The calibration surface {@code tools/ReaderBench} runs against: one {@link LatticeReader.RowFit}
     * per plate, the geometry ratios scored against the labelled corpus. Exercised here so it stays
     * working, and so a clean six-plate frame reads six plates through the record it exposes.
     */
    @Test
    void exposesPerRowFitsForCalibration() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");
        LatticeReader reader = new LatticeReader(Viewport.REFERENCE);

        List<LatticeReader.RowFit> fits = reader.rows(img, 6);
        assertEquals(6, fits.size());
        for (LatticeReader.RowFit f : fits) {
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

    static Stream<Arguments> darkFrames() {
        return FrameCorpus.darkFrames();
    }

    static Stream<Arguments> stripOffPlateFrames() {
        return FrameCorpus.stripOffPlateFrames();
    }

    static Stream<Arguments> fourPlateFrames() {
        return FrameCorpus.fourPlateFrames();
    }

    static Stream<Arguments> sweepFrames() {
        return FrameCorpus.sweepFrames();
    }
}
