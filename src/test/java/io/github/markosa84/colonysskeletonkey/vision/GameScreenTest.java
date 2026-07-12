package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

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
        grabber.frames = box -> counter(box, 3, 255); // three dark digit pixels on white
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        assertEquals(screen.pickCounterFingerprint(), screen.pickCounterFingerprint());
    }

    @Test
    void aDigitChangingChangesTheHash() {
        GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);

        grabber.frames = box -> counter(box, 3, 255);
        long fivePicks = screen.pickCounterFingerprint();
        grabber.frames = box -> counter(box, 4, 255); // one more dark pixel: the digits changed
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

        grabber.frames = box -> counter(box, 3, 255);
        long onWhite = screen.pickCounterFingerprint();
        grabber.frames = box -> counter(box, 3, 200); // still light, just dimmer
        long onGrey = screen.pickCounterFingerprint();

        assertEquals(onWhite, onGrey);
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

    /** The lockpick counter: {@code darkPixels} near-black digit pixels on a {@code bg}-grey box. */
    private static BufferedImage counter(Rectangle box, int darkPixels, int bg) {
        BufferedImage img = new BufferedImage(box.width, box.height, BufferedImage.TYPE_INT_RGB);
        int light = (bg << 16) | (bg << 8) | bg;
        for (int y = 0; y < box.height; y++) {
            for (int x = 0; x < box.width; x++) {
                img.setRGB(x, y, light);
            }
        }
        for (int i = 0; i < darkPixels; i++) {
            img.setRGB(10 + i, 10, 0x101010); // luminance 16: unambiguously a digit
        }
        return img;
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
