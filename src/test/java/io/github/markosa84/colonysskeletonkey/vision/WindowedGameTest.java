package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug a player reported at 2560x1440: "no 4-7 plate lock detected", over a saved screenshot that
 * looked perfectly fine.
 *
 * <p>It was never the resolution. 2560x1440 is one of the 23 modes {@code LockReaderTest} sweeps, and
 * the reader passes every frame of it. What broke was the <b>coordinate frame</b>: the tool measured
 * the primary display and assumed the game filled it, so a player whose game does <i>not</i> fill it -
 * running below the desktop resolution, in a window, or on a second monitor - had the reader scanning
 * a rectangle the lock was not in. It finds no pins there and reports no lock, while the dumped frame
 * shows the lock exactly where the player can see it. Nothing in the pipeline objects, because nothing
 * in the pipeline knows what the game's view was supposed to be.
 *
 * <p>So this test builds that desktop: a real 4K frame of a 5-plate lock, scaled down to a 2560x1440
 * game view and pasted into a 4K desktop at an offset. Then it reads it the old way and the new way.
 */
class WindowedGameTest {

    /** A 4K frame of the 5-plate chest lock. Its labels are {@code LockReaderTest}'s. */
    private static final String FRAME = "5p-plates-1-2-opposed/step-0.png";
    private static final int[] OFFSETS = {0, 3, -2, 3, 3};

    /** The game, at 2560x1440, sitting where Windows centres a window that size on a 4K desktop. */
    private static final Rectangle GAME = new Rectangle(640, 360, 2560, 1440);
    private static final Rectangle DESKTOP = new Rectangle(0, 0, 3840, 2160);

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is"
                        + " missing from this checkout");
    }

    private final BufferedImage desktop = desktopWithGameWindow();

    /** The report, reproduced: measure the display, and the lock is nowhere to be found. */
    @Test
    void measuringTheDisplayFindsNoLockAtAll() {
        LockReader reader = new LockReader(new Viewport(DESKTOP.width, DESKTOP.height));

        assertEquals(-1, reader.detectPlateCount(desktop),
                "this is the bug: the frame holds a perfectly readable lock");
    }

    /** And the fix: measure the game's window, and it is the ordinary 1440p case, which works. */
    @Test
    void measuringTheGameWindowReadsTheLock() {
        Viewport viewport = new Viewport(GAME.x, GAME.y, GAME.width, GAME.height);
        GameScreen screen = new GameScreen(new DesktopGrabber(desktop), viewport);
        LockReader reader = new LockReader(viewport);

        BufferedImage view = screen.capture();

        assertEquals(5, reader.detectPlateCount(view));
        assertArrayEquals(OFFSETS, reader.readState(view, 5));
    }

    /**
     * The grabs are taken from the desktop, but every image handed on is the game's view, with the
     * game's top-left at (0, 0) - which is why the reader needed no change at all.
     */
    @Test
    void theGrabsAreOnTheDesktopAndTheImagesAreTheGamesView() {
        Viewport viewport = new Viewport(GAME.x, GAME.y, GAME.width, GAME.height);
        DesktopGrabber grabber = new DesktopGrabber(desktop);
        GameScreen screen = new GameScreen(grabber, viewport);

        BufferedImage full = screen.capture();
        BufferedImage polled = screen.captureLock();

        assertEquals(GAME, grabber.asked.getFirst(), "the game's window, on the desktop");
        assertTrue(GAME.contains(grabber.asked.get(1)), "and the lock box within it");
        assertEquals(GAME.width, full.getWidth(), "but the image is the view, not the desktop");
        assertEquals(GAME.width, polled.getWidth());
        assertEquals(GAME.height, polled.getHeight());
    }

    /** A poll of the windowed game must decode as well as a full capture does. */
    @Test
    void thePollingPathReadsTheWindowedGameToo() {
        Viewport viewport = new Viewport(GAME.x, GAME.y, GAME.width, GAME.height);
        GameScreen screen = new GameScreen(new DesktopGrabber(desktop), viewport);

        LivePoller poller = new LivePoller(screen, new LockReader(viewport));

        assertArrayEquals(OFFSETS, poller.readLock(5));
    }

    /** A 4K desktop with the 1440p game window pasted into it - the reporter's screen. */
    private static BufferedImage desktopWithGameWindow() {
        BufferedImage frame = TestFrames.load(FRAME);
        BufferedImage out = new BufferedImage(DESKTOP.width, DESKTOP.height,
                BufferedImage.TYPE_INT_RGB);
        var g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(frame, GAME.x, GAME.y, GAME.width, GAME.height, null);
        g.dispose();
        return out;
    }

    /** Crops the desktop to whatever box is asked for - exactly what the live Robot hands back. */
    private static final class DesktopGrabber implements ScreenGrabber {
        private final BufferedImage desktop;
        final List<Rectangle> asked = new ArrayList<>();

        DesktopGrabber(BufferedImage desktop) {
            this.desktop = desktop;
        }

        @Override
        public BufferedImage grab(Rectangle box) {
            asked.add(new Rectangle(box));
            return desktop.getSubimage(box.x, box.y, box.width, box.height);
        }
    }
}
