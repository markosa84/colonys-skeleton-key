package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gamma probe: what it reads off the lockpick-counter panel, and what it refuses to read.
 *
 * <p>The reader's colour and luminance gates are absolute numbers fitted at one gamma, and the
 * game's slider spans 1.2 to 3.2. {@link Tone} is what makes those numbers true again for everyone
 * else - so the thing to protect here is not just that it works, but that it <b>knows when it
 * cannot</b>: a wrong tone is far more dangerous than none, because it makes the reader confidently
 * wrong rather than silent, and a false pin-pop tells the session a plate is centred when it is not.
 */
class ToneTest {

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the calibration frames are missing - see src/test/data/frames/README.md");
    }

    /**
     * The probe, against the slider. These eight readings are the family the curves are indexed by,
     * and the whole design rests on them being what they are: <b>ink</b> is monotone in the gamma
     * and dies at the bottom of the slider, <b>white</b> is pinned at 255 until exactly the point
     * where the ink stops saying anything, and then it moves. One of the two is always informative.
     */
    @ParameterizedTest(name = "gamma {0} reads ink {1}, white {2}")
    @CsvSource({
            "gamma/g-1.2/step-0.png,   0, 244",
            "gamma/g-1.5.png,          9, 253",
            "gamma/g-1.8.png,         24, 255",
            "gamma/g-2.1.png,         41, 255",
            "gamma/g-2.4.png,         58, 255",
            "gamma/g-2.7.png,         74, 255",
            "gamma/g-3.0.png,         91, 255",
            "gamma/g-3.2/step-0.png, 100, 255",
    })
    void theProbeReadsTheGammaOffThePanel(String frame, int ink, int white) {
        Tone tone = Tone.estimate(TestFrames.load(frame), Viewport.REFERENCE);

        assertEquals(ink, tone.ink(), frame + ": panel ink");
        assertEquals(white, tone.white(), frame + ": panel white");
    }

    /**
     * The calibrated gamma is the identity, exactly. This is the load-bearing one: it is why the
     * whole 217-frame corpus goes on vouching for every constant in {@link LockReader} unchanged,
     * and why a player at 2.7 sees no behaviour change at all.
     */
    @Test
    void theCalibratedGammaIsTheIdentity() {
        Tone tone = Tone.estimate(TestFrames.load("gamma/g-2.7.png"), Viewport.REFERENCE);

        assertTrue(tone.isCalibrated(), tone.describe());
        for (int v = 0; v < 256; v++) {
            assertEquals(v, tone.level(v), "level " + v + " must not move at the calibrated gamma");
        }
    }

    /** The panel is UI, so the same gamma reads the same whatever the game is rendering behind it. */
    @Test
    void theRoomBehindTheLockCannotMoveTheProbe() {
        Tone chest = Tone.estimate(TestFrames.load("gamma/g-3.2/step-0.png"), Viewport.REFERENCE);
        Tone other = Tone.estimate(TestFrames.load("gamma/g-3.2/step-6.png"), Viewport.REFERENCE);

        assertEquals(chest.ink(), other.ink());
        assertEquals(chest.white(), other.white());
    }

    /** A curve is a curve: it may not run backwards, or the reader's ordering stops meaning anything. */
    @ParameterizedTest
    @CsvSource({"gamma/g-1.2/step-0.png", "gamma/g-1.5.png", "gamma/g-2.1.png",
                "gamma/g-2.7.png", "gamma/g-3.2/step-0.png"})
    void everyCurveIsMonotone(String frame) {
        Tone tone = Tone.estimate(TestFrames.load(frame), Viewport.REFERENCE);

        for (int v = 1; v < 256; v++) {
            assertTrue(tone.level(v) >= tone.level(v - 1),
                    frame + ": level " + v + " maps below level " + (v - 1));
        }
    }

    /**
     * The gamma is read at whatever resolution the game is running: the panel is drawn at native
     * size, so the ink plateau survives all the way down to a 49x38 box. (Below 800x600 the tool
     * does not claim to work at all.)
     */
    @Test
    void theProbeSurvivesTheSmallestSupportedPanel() {
        Viewport viewport = new Viewport(2560, 1440);

        Tone tone = Tone.estimate(
                TestFrames.load("2560x1440/gamma-1.2-sweep/step-0.png"), viewport);

        assertEquals(0, tone.ink(), "the ink has crushed to black at the bottom of the slider");
        assertEquals(244, tone.white(), "so the white, which has lifted off 255, indexes instead");
    }

    // -- between the measured settings -------------------------------------------------------------

    /**
     * The slider is continuous and the family is eight points on it, so most players land between
     * two of them. A reading in between must give a curve in between - blended, not snapped to the
     * nearer neighbour, which would leave a player at 2.55 being corrected as though they were at
     * 2.7 and undo nothing.
     */
    @Test
    void aGammaBetweenTwoMeasuredOnesIsInterpolated() {
        int ink = LockReader.luminance(grey(66)); // between the 2.4 member (58) and the 2.7 one (74)

        Tone between = Tone.estimate(panel(ink, 255), Viewport.REFERENCE);
        Tone darker = Tone.estimate(TestFrames.load("gamma/g-2.4.png"), Viewport.REFERENCE);
        Tone calibrated = Tone.estimate(TestFrames.load("gamma/g-2.7.png"), Viewport.REFERENCE);

        assertEquals(ink, between.ink());
        assertFalse(between.isCalibrated(), "it is not the calibrated gamma, and must not act like it");
        for (int v = 0; v < 256; v++) {
            int lo = Math.min(darker.level(v), calibrated.level(v));
            int hi = Math.max(darker.level(v), calibrated.level(v));
            assertTrue(between.level(v) >= lo && between.level(v) <= hi,
                    "level " + v + " maps to " + between.level(v) + ", outside the two settings it "
                            + "sits between (" + lo + ".." + hi + ")");
        }
    }

    /**
     * Beyond the ends of the slider there is nothing measured, so the end curve is used rather than
     * an extrapolation. Extrapolating a tone curve is exactly the mistake that started this: a fit
     * pushed past its evidence mapped observed 255 back to 179 and destroyed the pins.
     */
    @Test
    void aReadingPastTheTopOfTheSliderClampsToItRatherThanExtrapolating() {
        Tone past = Tone.estimate(panel(LockReader.luminance(grey(140)), 255), Viewport.REFERENCE);
        Tone top = Tone.estimate(TestFrames.load("gamma/g-3.2/step-0.png"), Viewport.REFERENCE);

        for (int v = 0; v < 256; v++) {
            assertEquals(top.level(v), past.level(v), "level " + v);
        }
    }

    /**
     * A synthetic frame carrying nothing but a counter panel of the given ink and white, in the
     * proportions the real one has: the background is the bigger plateau, the digits the smaller.
     */
    private static BufferedImage panel(int ink, int white) {
        BufferedImage img = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        java.awt.Rectangle box = GameScreen.picksBox(Viewport.REFERENCE);
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                img.setRGB(x, y, grey(y < box.y + 2 * box.height / 3 ? white : ink));
            }
        }
        return img;
    }

    private static int grey(int level) {
        return (level << 16) | (level << 8) | level;
    }

    /**
     * A frame the gamma slider alone cannot have produced says so. The two anchors move together, so
     * an off-curve pair means a second setting is dimming the picture - which is exactly what the
     * frames from the user who reported this bug show (ink 13 beside a white of 184, where the
     * family says 254). Applying the nearest measured curve and keeping quiet would leave the next
     * maintainer with the same mystery this one started as.
     */
    @Test
    void aFrameDarkerThanTheSliderCanMakeItIsCalledOut() {
        int ink = LockReader.luminance(grey(13));
        int white = LockReader.luminance(grey(184)); // the family says ~254 at this ink

        Tone tone = Tone.estimate(panel(ink, white), Viewport.REFERENCE);

        assertFalse(tone.isGuess(), "the panel is right there - it is readable, just not explicable");
        assertTrue(tone.describe().contains("NOTE"), tone.describe());
        assertTrue(tone.describe().contains("brightness offset"), tone.describe());
    }

    /** Every real gamma setting is on the curve, so none of them trips that warning. */
    @ParameterizedTest
    @CsvSource({"gamma/g-1.5.png", "gamma/g-1.8.png", "gamma/g-2.1.png", "gamma/g-2.4.png",
                "gamma/g-2.7.png", "gamma/g-3.0.png", "gamma/g-3.2/step-0.png"})
    void theGammaSliderItselfIsNeverCalledOut(String frame) {
        Tone tone = Tone.estimate(TestFrames.load(frame), Viewport.REFERENCE);

        assertFalse(tone.describe().contains("NOTE"), frame + ": " + tone.describe());
    }

    // -- refusing to guess ------------------------------------------------------------------------

    /**
     * Nothing that looks like the panel means no tone. The fallback is the calibrated one, which is
     * exactly today's behaviour - never an invented curve.
     */
    @Test
    void aFrameWithNoPanelIsNotGuessedAt() {
        BufferedImage blank = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);

        Tone tone = Tone.estimate(blank, Viewport.REFERENCE);

        assertTrue(tone.isGuess(), tone.describe());
        assertEquals(128, tone.level(128), "a guess must not touch a single pixel");
    }

    /**
     * The panel half-off the frame is refused too. That happens when the <b>viewport</b> is wrong -
     * the other way this tool has failed in the field - and the sidecar says so, because a tone
     * quietly estimated from the edge of a screenshot would bury the real fault.
     */
    @Test
    void aPanelOutsideTheFrameIsRefusedAndSaysWhy() {
        BufferedImage tooSmall = new BufferedImage(1920, 1080, BufferedImage.TYPE_INT_RGB);

        Tone tone = Tone.estimate(tooSmall, Viewport.REFERENCE);

        assertTrue(tone.isGuess());
        assertTrue(tone.describe().contains("viewport"), tone.describe());
    }

    /**
     * A plateau is a plateau, not a handful of pixels. A blank white panel with a few stray dark
     * ones - a lock drawn over it, a mouse cursor - would otherwise hand back a tone fitted to
     * whatever those strays happened to be, which is the worst outcome available: a confident,
     * wrong curve.
     */
    @Test
    void aFewStrayDarkPixelsAreNotAnInkPlateau() {
        BufferedImage img = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        java.awt.Rectangle box = GameScreen.picksBox(Viewport.REFERENCE);
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                img.setRGB(x, y, grey(255));
            }
        }
        for (int x = box.x; x < box.x + 5; x++) {
            img.setRGB(x, box.y, grey(30)); // a smudge, not digits
        }

        Tone tone = Tone.estimate(img, Viewport.REFERENCE);

        assertTrue(tone.isGuess(), tone.describe());
    }

    /** A dark panel with no white plateau is not the counter, whatever else it is. */
    @Test
    void somethingDarkWhereThePanelShouldBeIsNotAPanel() {
        BufferedImage murky = new BufferedImage(3840, 2160, BufferedImage.TYPE_INT_RGB);
        for (int y = 1600; y < 1700; y++) {
            for (int x = 3090; x < 3190; x++) {
                murky.setRGB(x, y, 0x505050);
            }
        }

        Tone tone = Tone.estimate(murky, Viewport.REFERENCE);

        assertTrue(tone.isGuess(), tone.describe());
        assertFalse(tone.isCalibrated(), "a guess is not a measurement, even when it acts like one");
    }
}
