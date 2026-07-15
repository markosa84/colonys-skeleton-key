package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for {@link LockReader} against every labelled screenshot in
 * {@code src/test/data/frames/}: the 34-frame calibration census (4K), regression frames from
 * live failures, plus a 161-frame live sweep of one 5-plate lock at all 23 display modes of the
 * dev machine (800x600 through 4K), which pins the {@link Viewport} scaling against real
 * renders. Every constant in the reader is fitted to the 4K frames, so any tweak to them must
 * keep this suite green.
 *
 * <p>No game and no display are needed: {@link LockReader} is pure frame analysis (capturing
 * lives in {@link GameScreen}), so everything here reads PNGs headless.
 *
 * <p>Where the expected offsets come from:
 * <ul>
 *   <li><b>5p-plates-1-2-opposed/step-0</b> - the five offsets the user read off the screen and
 *       stated. <b>step-1..5</b> - the same lock as one plate is slid; plates 1 and 2 step by one
 *       in opposite directions per frame (an Inverted connection), every other plate frozen.</li>
 *   <li><b>6p-plate-5-sweep</b> - plate 5 stepped from one end of its track to the other: exactly
 *       the seven states -3..+3, nothing else moving.</li>
 *   <li><b>6p-plate-0-drags-1</b> - plate 0 slid from one end, dragging plate 1 the same way
 *       (Normal); plate 1 reaches -3 after three steps and blocks any further movement, which is
 *       why the user could only get half-way.</li>
 *   <li><b>6p-plates-0-and-5</b> - plate 0 slid end-to-end, then plate 5 slid end-to-end.</li>
 *   <li><b>6p-gap-shadow</b> - a difficulty-4 chest (live failure dump) whose inter-plate arch
 *       gap casts a hole-shaped shadow 2.25 spacings past the left end of plate 1's row. The
 *       user marked every real hole on a copy of this frame; the offsets follow from the hole
 *       lattice. Before the exact walk and the deskewed row gate the shadow read that row
 *       UNKNOWN (the double-skip window bridged onto it), so this frame pins both fixes.</li>
 *   <li><b>7p-*</b> - the first 7-plate lock ever captured, so its labels could not lean on a
 *       reader that was only ever verified at 4-6 plates. They were established the way the
 *       "Identifying which plate is selected" note prescribes: the lock's connections were probed
 *       live (a refused move leaves the lock untouched, so probing from one base configuration
 *       costs a strain and nothing else), and every state below was then <b>predicted from that
 *       model and the keys sent, before the frame was captured</b>. All 21 matched. Three
 *       independent signals back them up: the pin <b>pop</b> - a different code path, and a
 *       physically distinct game signal - lands on reported offset 0 in every frame, including
 *       ones with three and four simultaneous pops; moves were refused exactly where the model
 *       says a plate would run off its track; and a pixel diff of a swept plate's two extremes
 *       shows exactly one plate body moving, its pin standing still.</li>
 * </ul>
 * Each sequence is a chain of single steps, so a reader that is wrong on any one frame breaks the
 * arithmetic of the whole sequence - which is what makes these unlabelled frames usable as truth.
 */
class LockReaderTest {

    /**
     * The frames ship with the repository, so their absence is a broken checkout, not a supported
     * configuration - <b>fail</b>, never skip. A skip here would be the worst possible outcome: the
     * whole reader calibration would silently vanish and the suite would still report green.
     */
    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is"
                        + " missing from this checkout - the reader's entire calibration lives"
                        + " there");
    }

    private final LockReader reader = new LockReader(Viewport.REFERENCE);

    @ParameterizedTest(name = "{0} shows {1} plates")
    @CsvSource({
            "plate-count/4-plates.png, 4",
            "plate-count/5-plates.png, 5",
            "plate-count/6-plates.png, 6",
            // No plate-count/7-plates.png: it would be a byte-for-byte duplicate of a sweep frame,
            // and these are ~11MB each. Any 7-plate frame proves the same thing.
            "7p-plate-2-sweep/step-0.png, 7",
    })
    void detectsPlateCount(String frame, int expected) {
        assertEquals(expected, reader.detectPlateCount(TestFrames.load(frame)), frame);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("labelledFrames")
    void readsEveryPlateOffset(String frame, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        int n = reader.detectPlateCount(img);
        assertEquals(expected.length, n, frame + ": plate count");
        assertArrayEquals(expected, reader.readState(img, n), frame + ": offsets");
        // The two centering signals must agree: a plate reads offset 0 exactly when its pin is
        // popped (readState short-circuits centred plates from the pop, never from hole counting).
        boolean[] centered = reader.readCentered(img, n);
        for (int i = 0; i < n; i++) {
            assertEquals(expected[i] == 0, centered[i], frame + ": centered flag of plate " + i);
        }
    }

    /**
     * Every display mode of the dev machine. At each one, the same key protocol (R, A x2, then
     * six D presses) was replayed against the live game and a frame captured per settled state;
     * the state labels were read once at 4K with the validated reader and transfer to every
     * resolution because game state depends on keys, not pixels. See
     * {@code 3840x2160/front-plate-sweep/labels.txt} for the provenance record.
     */
    static final String[] SWEEP_MODES = {
            "3840x2160", "2560x1600", "2560x1440", "2048x1536", "1920x1440", "1920x1200",
            "1920x1080", "1680x1050", "1600x1200", "1600x1024", "1600x900", "1440x1080",
            "1366x768", "1360x768", "1280x1024", "1280x960", "1280x800", "1280x768",
            "1280x720", "1176x664", "1152x864", "1024x768", "800x600",
    };

    @ParameterizedTest(name = "{0}")
    @MethodSource("sweepFrames")
    void readsTheFrontPlateSweepAtEveryResolution(String frame, Viewport viewport, int[] expected) {
        LockReader scaled = new LockReader(viewport);
        BufferedImage img = TestFrames.load(frame);
        assertEquals(expected.length, scaled.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, scaled.readState(img, expected.length), frame + ": offsets");
    }

    // -- a smaller fan is not a smaller lock -------------------------------------------------------

    /**
     * <b>A 6-plate lock may never be answered as a 4-plate one.</b> The fans of {@code n} and
     * {@code n+2} share a lattice - a 6-plate lock's pins cover a 4-plate fan exactly, and a 7-plate
     * lock covers a 5-plate one - so "the largest fan that fits" is only a safe answer while every
     * pin is seen. Lose the faintest one (which is what a dark gamma does: a real reported frame
     * loses exactly one) and the reader used to answer the <b>smaller lock, silently</b>, handing
     * the session a model with the wrong number of plates to drive into walls.
     *
     * <p>Here the front pin is painted out of a real 6-plate frame - the failure, reproduced. The
     * only acceptable answers are 6 (if it can still be seen) or nothing at all. Never 4.
     */
    @Test
    void aSixPlateLockWithAFaintFrontPinIsNeverMistakenForAFourPlateOne() {
        BufferedImage img = TestFrames.load("6p-plate-5-sweep/step-0.png");
        assertEquals(6, reader.detectPlateCount(img), "the frame really is a 6-plate lock");

        double[] frontPin = reader.pinPosition(6, 5);
        blackOut(img, (int) frontPin[0], (int) frontPin[1], 60);

        assertEquals(-1, reader.detectPlateCount(img),
                "with its front pin invisible this is not a readable lock - and it is certainly not "
                        + "a 4-plate one, whose fan its remaining pins cover exactly");
    }

    /** The same trap one lattice up: a 7-plate lock's pins cover a 5-plate fan. */
    @Test
    void aSevenPlateLockWithAFaintFrontPinIsNeverMistakenForAFivePlateOne() {
        BufferedImage img = TestFrames.load("7p-plate-2-sweep/step-0.png");
        assertEquals(7, reader.detectPlateCount(img));

        double[] frontPin = reader.pinPosition(7, 6);
        blackOut(img, (int) frontPin[0], (int) frontPin[1], 60);

        assertEquals(-1, reader.detectPlateCount(img));
    }

    /** Either end of the fan, not just the front: the plate left over may be the back one. */
    @Test
    void aSixPlateLockWithAFaintBackPinIsNotAFourPlateOneEither() {
        BufferedImage img = TestFrames.load("6p-plate-5-sweep/step-0.png");

        double[] backPin = reader.pinPosition(6, 0);
        blackOut(img, (int) backPin[0], (int) backPin[1], 60);

        assertEquals(-1, reader.detectPlateCount(img));
    }

    /** And the diagnosis says which trap it fell into, because the count alone would not show it. */
    @Test
    void describeExplainsAFanThatIsOnlyTheMiddleOfABiggerOne() {
        BufferedImage img = TestFrames.load("6p-plate-5-sweep/step-0.png");
        double[] frontPin = reader.pinPosition(6, 5);
        blackOut(img, (int) frontPin[0], (int) frontPin[1], 60);

        String report = reader.describe(img);

        assertTrue(report.contains("one step past the end"), report);
        assertTrue(report.contains("6-plate fan"), report);
    }

    /**
     * <b>The bug a user actually paid for.</b> The two tests above lose <i>one</i> end pin, and the
     * fan is still refused because the other end pin is right there to be seen. A dark or
     * HDR-tonemapped frame is not so kind: it takes the faintest pins, and the faintest pins are the
     * two on the ends. Lose both and what is left <b>is</b> a 4-plate fan, with nothing beyond either
     * end to give it away.
     *
     * <p>That is what a reporter's console shows: {@code Detected 4 plates at [-2, -3, 1, -1]} on a
     * 6-plate chest, then nine strains against plates that were never going to move, then "Stuck".
     * Only the <b>pins</b> are painted out here - the plates and their hole rows are untouched,
     * because that is what a pin too faint to see leaves behind, and it is what lets the reader know
     * it is being lied to.
     */
    @Test
    void aSixPlateLockWithBothEndPinsInvisibleIsNeverAFourPlateOne() {
        BufferedImage img = TestFrames.load("6p-plate-5-sweep/step-0.png");
        assertEquals(6, reader.detectPlateCount(img), "the frame really is a 6-plate lock");

        blackOutPin(img, 6, 0);
        blackOutPin(img, 6, 5);

        assertEquals(-1, reader.detectPlateCount(img),
                "with both end pins invisible the remaining four cover a 4-plate fan exactly - but "
                        + "a plate's hole row still sits one step past each end, so this is the "
                        + "middle of a 6-plate lock and the only honest answer is none");
    }

    /** The same trap one lattice up, and the same two pins: a 7-plate lock covers a 5-plate fan. */
    @Test
    void aSevenPlateLockWithBothEndPinsInvisibleIsNeverAFivePlateOne() {
        BufferedImage img = TestFrames.load("7p-plate-2-sweep/step-0.png");
        assertEquals(7, reader.detectPlateCount(img));

        blackOutPin(img, 7, 0);
        blackOutPin(img, 7, 6);

        assertEquals(-1, reader.detectPlateCount(img));
    }

    /**
     * A frame full of spurious blobs keeps the right answer - and no longer needs an allowance to do
     * it. At low resolutions a pin fragments into several blobs (15 for a 5-plate lock at 2048x1536),
     * and the room adds its own warm dots; the check that asked the <i>pins</i> what lay past the end
     * of a fan had to be switched off on frames like this one, which is precisely the hole the
     * wrong-model bug walked through. Asking the hole rows instead, clutter is simply not a category
     * the question has: a candle is not a row of six holes.
     */
    @Test
    void aClutteredFrameStillTrustsTheLargestFanThatFits() {
        Viewport viewport = new Viewport(2048, 1536);
        LockReader scaled = new LockReader(viewport);

        assertEquals(5, scaled.detectPlateCount(
                TestFrames.load("2048x1536/front-plate-sweep/step-0.png")));
    }

    /** Paints out a plate's pin, leaving its plate and hole row exactly as they were. */
    private void blackOutPin(BufferedImage img, int n, int plate) {
        double[] pin = reader.pinPosition(n, plate);
        blackOut(img, (int) pin[0], (int) pin[1], 22);
    }

    /** Paints a square of the frame black, so the reader cannot find what was there. */
    private static void blackOut(BufferedImage img, int cx, int cy, int radius) {
        for (int y = cy - radius; y <= cy + radius; y++) {
            for (int x = cx - radius; x <= cx + radius; x++) {
                img.setRGB(x, y, 0);
            }
        }
    }

    // -- the game's gamma slider -------------------------------------------------------------------

    /**
     * The same 7-plate lock, the same key protocol, at both ends of the game's gamma slider - the
     * setting that broke this reader in the field. Raw, these frames fail in opposite ways: at 1.2
     * the brass falls under {@code isPin}'s {@code r >= 130} and <b>no fan fits at all</b> (the
     * reported bug); at 3.2 the pins survive but the hole rows do not, and no pin reaches
     * {@code CENTERED_MIN_PIXELS}, so the pop signal - the only thing that confirms an open lock -
     * is dead.
     *
     * <p>Read through the {@link Tone} the frame itself carries, all of it comes back. Note what is
     * being asserted: not "close enough", but the <b>same labels</b> the calibrated fixtures give,
     * with the pin-pop and the hole count agreeing on every plate of every frame. Only step-0 of
     * each sweep went into fitting the curves, so steps 1-6 are a straight holdout.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gammaFrames")
    void readsTheSameLockAtAnyGamma(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LockReader toned = new LockReader(viewport, Tone.estimate(img, viewport));

        assertEquals(expected.length, toned.detectPlateCount(img), frame + ": plate count");
        assertArrayEquals(expected, toned.readState(img, expected.length), frame + ": offsets");
        boolean[] centered = toned.readCentered(img, expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i] == 0, centered[i], frame + ": centered flag of plate " + i);
        }
    }

    /**
     * The 7-plate chest ({@code START = {0,-2,-3,3,3,2,3}}), plate 2 swept end to end - it drags
     * nothing, so it moves alone and the labels are the protocol's, not a reading's. The same
     * sequence was replayed at each gamma, which is why the labels transfer: game state depends on
     * the keys sent, not on the pixels that come back. See {@code gamma/labels.txt}.
     */
    static Stream<Arguments> gammaFrames() {
        List<Arguments> frames = new ArrayList<>();
        for (String gamma : new String[] {"1.2", "3.2"}) {
            for (int k = 0; k <= 6; k++) {
                frames.add(Arguments.of("gamma/g-" + gamma + "/step-" + k + ".png",
                        Viewport.REFERENCE, new int[] {0, -2, k - 3, 3, 3, 2, 3}));
            }
        }
        // The dark end at the reporter's own resolution: gamma and scale are independent faults, and
        // this is the one configuration that was actually reported broken.
        for (int k = 0; k <= 6; k++) {
            frames.add(Arguments.of("2560x1440/gamma-1.2-sweep/step-" + k + ".png",
                    new Viewport(2560, 1440), new int[] {0, -2, k - 3, 3, 3, 2, 3}));
        }
        // The rest of the slider, at START. Every one of these is a different transfer curve.
        for (String gamma : new String[] {"1.5", "1.8", "2.1", "2.4", "2.7", "3.0"}) {
            frames.add(Arguments.of("gamma/g-" + gamma + ".png",
                    Viewport.REFERENCE, new int[] {0, -2, -3, 3, 3, 2, 3}));
        }
        return frames.stream();
    }

    /**
     * <b>The gate that keeps the calibration honest.</b> Every frame the reader was fitted on must
     * measure as the calibrated tone - so the estimator resolves to the identity there, every
     * constant in {@link LockReader} keeps the exact meaning it was measured with, and all 217 of
     * these frames go on vouching for it unchanged. If this fails, the tone is rewriting the very
     * pixels it was calibrated against.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("calibratedFrames")
    void everyCalibrationFrameMeasuresAsTheCalibratedGamma(String frame, Viewport viewport) {
        Tone tone = Tone.estimate(TestFrames.load(frame), viewport);

        assertTrue(tone.isCalibrated(), frame + ": " + tone.describe());
    }

    static Stream<Arguments> calibratedFrames() {
        Stream<Arguments> census = labelledFrames()
                .map(a -> Arguments.of(a.get()[0], Viewport.REFERENCE));
        Stream<Arguments> sweep = sweepFrames()
                .map(a -> Arguments.of(a.get()[0], a.get()[1]));
        Stream<Arguments> counts = Stream.of("4-plates", "5-plates", "6-plates")
                .map(n -> Arguments.of("plate-count/" + n + ".png", Viewport.REFERENCE));
        return Stream.concat(census, Stream.concat(sweep, counts));
    }

    static Stream<Arguments> sweepFrames() {
        List<Arguments> frames = new ArrayList<>();
        for (String mode : SWEEP_MODES) {
            String[] wh = mode.split("x");
            Viewport viewport = new Viewport(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            for (int k = 0; k <= 6; k++) {
                // The front plate (index 4) was swept +3..-3; the rest of the lock never moved.
                frames.add(Arguments.of(mode + "/front-plate-sweep/step-" + k + ".png",
                        viewport, new int[] {3, 1, 2, 0, 3 - k}));
            }
        }
        return frames.stream();
    }

    static Stream<Arguments> labelledFrames() {
        List<Arguments> frames = new ArrayList<>();
        // 5 plates: plates 1 and 2 slide in opposite directions.
        frames.add(frame("5p-plates-1-2-opposed/step-0.png", 0, 3, -2, 3, 3));
        frames.add(frame("5p-plates-1-2-opposed/step-1.png", 0, 2, -1, 3, 3));
        frames.add(frame("5p-plates-1-2-opposed/step-2.png", 0, 1, 0, 3, 3));
        frames.add(frame("5p-plates-1-2-opposed/step-3.png", 0, 0, 1, 3, 3));
        frames.add(frame("5p-plates-1-2-opposed/step-4.png", 0, -1, 2, 3, 3));
        frames.add(frame("5p-plates-1-2-opposed/step-5.png", 0, -2, 3, 3, 3));
        // 6 plates: plate 5 through all seven states.
        for (int k = 0; k < 7; k++) {
            frames.add(frame("6p-plate-5-sweep/step-" + k + ".png", 3, 0, 1, 2, -3, k - 3));
        }
        // 6 plates: plate 0 half-way, blocked by the plate it drags.
        for (int k = 0; k < 4; k++) {
            frames.add(frame("6p-plate-0-drags-1/step-" + k + ".png", 3 - k, -k, 1, 2, -3, -2));
        }
        // 6 plates: plate 0 end-to-end, then plate 5 end-to-end.
        for (int k = 0; k < 6; k++) {
            frames.add(frame("6p-plates-0-and-5/step-0" + k + ".png", 3 - k, -1, 0, 2 - k, 3, -2));
        }
        // step-05 -> step-06 is not another plate-0 step: plate 3 is already at -3, so that move
        // would be invalid. Plate 2 was moved instead, dragging plate 0 with it.
        frames.add(frame("6p-plates-0-and-5/step-06.png", -3, -1, -1, -3, 3, -2));
        for (int k = 0; k < 7; k++) {
            frames.add(frame("6p-plates-0-and-5/step-" + String.format("%02d", 7 + k) + ".png",
                    -3, -1, -1, -3, 3, k - 3));
        }
        // 6 plates, difficulty-4 chest: an arch-gap shadow lattice-aligned past the left end of
        // plate 1's row once read that row UNKNOWN. Pins the exact walk + the deskewed row gate.
        frames.add(frame("6p-gap-shadow/step-0.png", 2, 1, 1, -2, 3, -3));

        // 7 plates (a difficulty-4 chest). Until these frames the 7-plate fan geometry and the
        // rotation angle were pure extrapolation - no 7-plate screenshot existed. START is
        // {0,-2,-3,3,3,2,3}; the connections, probed live, are 0->4(N), 3->1(N),4(N), 6->1(N),
        // and plates 1, 2 and 5 drag nothing.
        //
        // Plate 2 drags nothing and starts at -3, so it sweeps its whole track alone.
        for (int k = 0; k <= 6; k++) {
            frames.add(frame("7p-plate-2-sweep/step-" + k + ".png", 0, -2, k - 3, 3, 3, 2, 3));
        }
        // The FRONT row, end to end: plate 6 drags plate 1 (Normal), and plate 1 was first raised
        // clear to +3 so the pair can descend together without either leaving its track.
        for (int k = 0; k <= 6; k++) {
            frames.add(frame("7p-plate-6-drags-1/step-" + k + ".png",
                    0, 3 - k, -3, 3, 3, 2, 3 - k));
        }
        // The BACK row, end to end: plate 0 drags plate 4 (Normal). Plate 3 (which drags 4 but not
        // 0) was used first to bring plate 4 down to 0, aligning the pair so they can sweep +3..-3
        // together. Front and back are the rows where one global rotation angle fits worst, so
        // these two sequences are what the extrapolated geometry most needed.
        for (int k = 0; k <= 6; k++) {
            frames.add(frame("7p-plate-0-drags-4/step-" + k + ".png",
                    3 - k, 0, -3, 0, 3 - k, 2, 3));
        }
        return frames.stream();
    }

    private static Arguments frame(String path, int... expected) {
        return Arguments.of(path, expected);
    }
}
