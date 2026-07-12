package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
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
