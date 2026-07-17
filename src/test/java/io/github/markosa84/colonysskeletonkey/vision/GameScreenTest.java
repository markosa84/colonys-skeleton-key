package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The capture side of the vision layer, driven through the {@link ScreenGrabber} seam instead of a
 * live {@code Robot}, so it runs headless like everything else.
 *
 * <p>What matters here is not that pixels arrive - it is <i>where</i> they land. The reader works in
 * absolute screen coordinates, so a lock box composited one pixel off would silently mis-read every
 * frame. {@code CaptureBoxTest} proves the box is big enough; this proves it is in the right place.
 */
class GameScreenTest {

    /** The reference (4K) boxes, as calibrated. Both are what the live Robot is asked to grab. */
    private static final Rectangle LOCK_BOX_4K = new Rectangle(2450, 300, 1300, 1120);
    private static final Rectangle PICKS_BOX_4K = new Rectangle(3104, 1616, 72, 56);

    private static final int BLACK = 0xFF000000;
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final int GREEN = 0xFF00FF00;

    private final FakeGrabber grabber = new FakeGrabber();

    @Test
    void captureGrabsTheWholeScreen() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        BufferedImage img = screen.capture();

        assertEquals(new Rectangle(0, 0, 3840, 2160), grabber.asked.getFirst());
        assertEquals(3840, img.getWidth());
        assertEquals(2160, img.getHeight());
    }

    @Test
    void theLockBoxIsTheCalibratedOneAtTheReferenceResolution() {
        assertEquals(LOCK_BOX_4K, GameScreen.lockBox(Viewport.REFERENCE));
    }

    /** Origin floored, extent ceiled: a rounded-down box would shave a pixel row off the lock. */
    @Test
    void aScaledLockBoxRoundsOutwardsSoNothingIsCut() {
        Viewport half = new Viewport(1920, 1080);

        // At half scale the 4K box (2450,300,1300,1120) maps to (1225,150) and 650x560 exactly.
        assertEquals(new Rectangle(1225, 150, 650, 560), GameScreen.lockBox(half));

        Viewport awkward = new Viewport(1366, 768); // a scale that lands on no integer at all
        Rectangle box = GameScreen.lockBox(awkward);
        assertTrue(box.x <= awkward.x(2450), "origin floored");
        assertTrue(box.y <= awkward.y(300), "origin floored");
        assertTrue(box.width >= awkward.len(1300), "extent ceiled");
        assertTrue(box.height >= awkward.len(1120), "extent ceiled");
    }

    /**
     * The lock grab is composited into a full-frame canvas at its own screen offset, so every
     * absolute coordinate in {@link LockReader} still lands on the right pixel - that is the whole
     * trick that makes a ~23ms poll behave like a ~79ms full capture.
     */
    @Test
    void captureLockCompositesTheGrabAtItsScreenPosition() {
        grabber.frames = box -> corners(box.width, box.height);
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        BufferedImage canvas = screen.captureLock();

        assertEquals(LOCK_BOX_4K, grabber.asked.getFirst(), "polls the lock box, never the screen");
        assertEquals(3840, canvas.getWidth(), "a full-frame canvas, in screen coordinates");
        assertEquals(2160, canvas.getHeight());
        assertEquals(BLUE, canvas.getRGB(LOCK_BOX_4K.x, LOCK_BOX_4K.y),
                "the grab's top-left pixel belongs at the box's top-left");
        assertEquals(GREEN, canvas.getRGB(LOCK_BOX_4K.x + LOCK_BOX_4K.width - 1,
                LOCK_BOX_4K.y + LOCK_BOX_4K.height - 1), "and its bottom-right at the box's");
        assertEquals(RED, canvas.getRGB(LOCK_BOX_4K.x + 10, LOCK_BOX_4K.y + 10));
        assertEquals(BLACK, canvas.getRGB(0, 0), "outside the box the canvas is untouched");
    }

    /** The canvas is reused: allocating a 4K image per poll would cost more than the grab. */
    @Test
    void captureLockReusesOneCanvas() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        assertSame(screen.captureLock(), screen.captureLock());
    }

    /**
     * When the game does not fill the display, every grab has to be taken from where the window
     * actually is - while the images handed on stay the game's own view, so the reader keeps
     * working in the coordinates it was calibrated in. Get this backwards and the tool scans the
     * desktop for a lock that is drawn 640px to the right of where it looks.
     */
    @Test
    void aWindowedGameIsGrabbedAtItsPositionOnTheDesktop() {
        Viewport windowed = new Viewport(640, 360, 2560, 1440);
        GameScreen screen = new GameScreen(grabber, windowed);

        BufferedImage img = screen.capture();
        screen.captureLock();
        screen.pickCounterFingerprint();

        assertEquals(new Rectangle(640, 360, 2560, 1440), grabber.asked.get(0), "the window");
        assertEquals(2560, img.getWidth(), "but the image is the view: the reader sees no offset");
        Rectangle lock = GameScreen.lockBox(windowed);
        assertEquals(new Rectangle(lock.x + 640, lock.y + 360, lock.width, lock.height),
                grabber.asked.get(1), "the lock box, translated onto the desktop");
        assertEquals(new Rectangle((int) windowed.x(3104) + 640, (int) windowed.y(1616) + 360,
                (int) Math.ceil(windowed.len(72)), (int) Math.ceil(windowed.len(56))),
                grabber.asked.get(2), "and so is the lockpick counter");
    }

    /** The canvas is the game's view, so the composite lands where the reader expects it. */
    @Test
    void aWindowedGamesCanvasIsStillViewLocal() {
        grabber.frames = box -> corners(box.width, box.height);
        Viewport windowed = new Viewport(640, 360, 2560, 1440);
        GameScreen screen = new GameScreen(grabber, windowed);

        BufferedImage canvas = screen.captureLock();
        Rectangle lock = GameScreen.lockBox(windowed);

        assertEquals(2560, canvas.getWidth(), "the view, not the desktop");
        assertEquals(1440, canvas.getHeight());
        assertEquals(BLUE, canvas.getRGB(lock.x, lock.y),
                "the grab's top-left belongs at the box's view-local top-left");
    }

    @Test
    void theCounterFingerprintReadsTheCalibratedCounterBox() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        screen.pickCounterFingerprint();

        assertEquals(PICKS_BOX_4K, grabber.asked.getFirst());
    }

    /**
     * The counter is white with dark digits, and the hash thresholds every pixel to dark-or-light.
     * So the number of lockpicks changing moves the hash - and nothing else does. Two calls on the
     * same counter must agree, or every clean move would look like a broken pick.
     */
    @Test
    void theSameCounterAlwaysHashesTheSame() {
        grabber.frames = box -> counter(box, 300, 74, 255);
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        assertEquals(screen.pickCounterFingerprint(), screen.pickCounterFingerprint());
    }

    @Test
    void aDigitChangingChangesTheHash() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        grabber.frames = box -> counter(box, 300, 74, 255);
        long fivePicks = screen.pickCounterFingerprint();
        grabber.frames = box -> counter(box, 301, 74, 255); // one more ink pixel: the digits changed
        long fourPicks = screen.pickCounterFingerprint();

        assertNotEquals(fivePicks, fourPicks, "a pick broke, and the counter says so");
    }

    /**
     * It is a <i>thresholded</i> hash, not a pixel hash: the box getting brighter or dimmer while
     * the digits stay dark must not fake a break. Only dark-vs-light crossings count.
     */
    @Test
    void aBrighterCounterWithTheSameDigitsHashesTheSame() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        grabber.frames = box -> counter(box, 300, 74, 255);
        long onWhite = screen.pickCounterFingerprint();
        grabber.frames = box -> counter(box, 300, 74, 200); // still light, just dimmer
        long onGrey = screen.pickCounterFingerprint();

        assertEquals(onWhite, onGrey);
    }

    /**
     * A box with no panel in it at all - which is what a viewport pointing at the wrong rectangle
     * grabs - must still hash deterministically. There is nothing useful to say about a counter that
     * is not there, and this is not the method that should say it: a wrong viewport is reported far
     * more loudly by the reader finding no lock.
     */
    @Test
    void aBoxWithNoPanelInItStillHashesDeterministically() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE); // the fake grabs black

        assertEquals(screen.pickCounterFingerprint(), screen.pickCounterFingerprint());
    }

    /**
     * <b>Every panel the game has ever been measured showing, and the hash sees the digits in all of
     * them - with no tone curve and no absolute level anywhere in it.</b> The pairs are the gamma
     * slider's own signature (CLAUDE.md's table, 0/244 at gamma 1.2 through 100/255 at 3.2) plus the
     * two an HDR tonemap produces, which are off the slider's range entirely and are what the
     * reporters' frames measure.
     *
     * <p>The old absolute cut of 110 passes most of this list too, and measured, it was not broken
     * on any real frame in the corpus. What it did not have was a <i>reason</i> to keep working: it
     * cleared gamma 3.2's digits by ten levels, and it cleared an HDR panel's only because the tone
     * put a wrong-family curve on them. This list is the shape of the argument that replaces that
     * luck - each pair is a fact about the game, and the cut is derived from the pair rather than
     * asserted over it.
     */
    @ParameterizedTest(name = "ink {0} on white {1}")
    @CsvSource({
            "0, 244",    // gamma 1.2 - the ink has crushed to black
            "9, 253",    // gamma 1.5
            "24, 255",   // gamma 1.8
            "41, 255",   // gamma 2.1
            "58, 255",   // gamma 2.4
            "74, 255",   // gamma 2.7 - the calibration
            "91, 255",   // gamma 3.0
            "100, 255",  // gamma 3.2 - the top of the slider
            "11, 183",   // HDR, as three reporters' frames measure it
            "36, 199",
    })
    void theHashFollowsTheDigitsAtEveryPanelTheGameHasBeenMeasuredShowing(int ink, int white) {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        grabber.frames = box -> counter(box, 300, ink, white);
        long fivePicks = screen.pickCounterFingerprint();
        grabber.frames = box -> counter(box, 301, ink, white);
        long fourPicks = screen.pickCounterFingerprint();

        assertNotEquals(fivePicks, fourPicks,
                "ink " + ink + " on white " + white + ": the digits changed and the hash did not, "
                        + "so a broken pick would be invisible");
    }

    /**
     * <b>The same thing, on real panels rather than modelled ones</b>, over the whole gamma slider.
     * Blanking the counter out must move the hash - which is exactly the claim that the hash is made
     * of the digits. A reader that has gone blind hashes the blanked box and the real one to the
     * same value, because to it they are both "all light".
     *
     * <p>The corpus has no frame pair where the counter really changes (no pick was ever spent
     * capturing it), so the digits are removed rather than decremented. It is the same question
     * asked backwards, and it needs no lockpick.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("gammaFrames")
    void theHashIsMadeOfTheDigitsOnEveryRealPanelInTheCorpus(String frame, Viewport viewport) {
        BufferedImage real = TestFrames.load(frame);
        BufferedImage blanked = withoutTheDigits(real, GameScreen.picksBox(viewport));

        long withDigits = new GameScreen(new FrameGrabber(real), viewport).pickCounterFingerprint();
        long without = new GameScreen(new FrameGrabber(blanked), viewport).pickCounterFingerprint();

        assertNotEquals(withDigits, without,
                frame + ": the counter's digits do not reach the hash, so a broken pick would be "
                        + "invisible at this gamma");
    }

    static Stream<Arguments> gammaFrames() {
        return FrameCorpus.gammaFrames().map(a -> Arguments.of(a.get()[0], a.get()[1]));
    }

    /** The counter box painted over with its own panel white: the number gone, the panel intact. */
    private static BufferedImage withoutTheDigits(BufferedImage frame, Rectangle box) {
        BufferedImage out = new BufferedImage(frame.getWidth(), frame.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        out.getGraphics().drawImage(frame, 0, 0, null);
        int panel = frame.getRGB(box.x + box.width / 2, box.y + 1); // the panel's own top edge
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                out.setRGB(x, y, panel);
            }
        }
        return out;
    }

    /** Crops a whole frame to whatever box is asked for - what the live Robot hands back. */
    private record FrameGrabber(BufferedImage frame) implements ScreenGrabber {
        @Override
        public BufferedImage grab(Rectangle box) {
            return frame.getSubimage(box.x, box.y, box.width, box.height);
        }
    }

    /** An image whose corners are identifiable, so a composite that slips can be caught. */
    private static BufferedImage corners(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, RED);
            }
        }
        img.setRGB(0, 0, BLUE);
        img.setRGB(w - 1, h - 1, GREEN);
        return img;
    }

    /** The lockpick counter: {@code inkPixels} digit pixels at {@code ink} on a {@code white} box. */
    private static BufferedImage counter(Rectangle box, int inkPixels, int ink, int white) {
        BufferedImage img = new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < box.height; y++) {
            for (int x = 0; x < box.width; x++) {
                img.setRGB(x, y, grey(white));
            }
        }
        for (int i = 0; i < inkPixels; i++) {
            img.setRGB(i % box.width, i / box.width, grey(ink));
        }
        return img;
    }

    private static int grey(int level) {
        return (level << 16) | (level << 8) | level;
    }

    /** Hands back whatever the test says is on screen, and remembers what was asked for. */
    private static final class FakeGrabber implements ScreenGrabber {
        final List<Rectangle> asked = new ArrayList<>();
        Function<Rectangle, BufferedImage> frames =
                box -> new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_RGB);

        @Override
        public BufferedImage grab(Rectangle box) {
            asked.add(new Rectangle(box));
            return frames.apply(box);
        }
    }
}
