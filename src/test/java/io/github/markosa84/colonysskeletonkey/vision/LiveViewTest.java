package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markosa84.colonysskeletonkey.Stdout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LivePoller} and {@link LiveLockView} - the two adapters that join the capture side to the
 * reader - driven by a <b>real labelled frame</b> served through the {@link ScreenGrabber} seam.
 * The fake grabber crops the frame exactly like {@code Robot} would, so this is the whole live
 * pipeline (grab, composite, decode) minus the screen.
 *
 * <p>The load-bearing distinction under test is which capture each one uses. Polling goes through
 * {@link GameScreen#captureLock()} because the grab, not the decode, is what a sample costs; a dump
 * and a plate count go through the full {@link GameScreen#capture()} because every pixel outside the
 * lock box of the composite is stale, and a dump has to be re-readable evidence.
 */
class LiveViewTest {

    /** A 4K frame of the 5-plate chest lock: plate 0 centred, the rest off-centre. */
    private static final String FRAME = "5p-plates-1-2-opposed/step-0.png";
    private static final int[] OFFSETS = {0, 3, -2, 3, 3};

    private static final Rectangle FULL_SCREEN = new Rectangle(0, 0, 3840, 2160);
    private static final Rectangle LOCK_BOX = new Rectangle(2450, 300, 1300, 1120);
    private static final Rectangle PICKS_BOX = new Rectangle(3104, 1616, 72, 56);

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is"
                        + " missing from this checkout");
    }

    private final BufferedImage frame = TestFrames.load(FRAME);
    private final CroppingGrabber grabber = new CroppingGrabber(frame);
    private final GameScreen screen = new GameScreen(grabber, Viewport.REFERENCE);
    private final LockReader reader = new LockReader(Viewport.REFERENCE);

    @Test
    void thePollerDecodesTheLockOutOfTheCheapLockBoxGrab() {
        LivePoller poller = new LivePoller(screen, reader);

        assertArrayEquals(OFFSETS, poller.readLock(5));
        assertEquals(List.of(LOCK_BOX), grabber.asked,
                "a poll grabs the lock box and nothing else - the full screen costs 3x as much");
    }

    @Test
    void thePollerFingerprintsTheLockpickCounter() {
        LivePoller poller = new LivePoller(screen, reader);

        long first = poller.pickFingerprint();

        assertEquals(List.of(PICKS_BOX), grabber.asked);
        assertEquals(first, poller.pickFingerprint(), "an unchanged counter cannot change its hash");
    }

    @Test
    void theViewCountsPlatesFromTheFullFrame() {
        LiveLockView view = new LiveLockView(screen, reader, () -> "");

        assertEquals(5, view.detectPlateCount());
        assertEquals(List.of(FULL_SCREEN), grabber.asked);
    }

    /**
     * A dump must be the <b>full</b> frame. {@code captureLock}'s canvas is stale everywhere outside
     * the lock box, so saving it would hand the next debugging session a lie.
     */
    @Test
    void dumpingAFrameSavesTheWholeScreenThroughCaptures(@TempDir Path dir) throws Exception {
        LiveLockView view = new LiveLockView(screen, reader, captures(dir), () -> "");

        Stdout.capturing(() -> view.dumpFrame("no-lock"));

        assertEquals(List.of(FULL_SCREEN), grabber.asked);
        Path dumped = dir.resolve("no-lock-20260712-102030-123.png");
        BufferedImage saved = ImageIO.read(dumped.toFile());
        assertEquals(3840, saved.getWidth(), "the whole screen, not the lock box");
        assertEquals(2160, saved.getHeight());
    }

    /**
     * And it must say <b>which rectangle</b> it thought it was reading. The failure a dump usually
     * documents - the viewport describing something other than the game's view - leaves a frame that
     * looks entirely normal to whoever reports it, so the frame alone cannot settle anything. The
     * sidecar carries the coordinates, and the reader's own account of what it found at them.
     */
    @Test
    void aDumpCarriesTheDiagnosticsThatTheFrameCannot(@TempDir Path dir) throws Exception {
        LiveLockView view = new LiveLockView(screen, reader, captures(dir),
                () -> "display:  3840x2160, awt scale 1.00\n");

        Stdout.capturing(() -> view.dumpFrame("no-lock"));

        String notes = Files.readString(dir.resolve("no-lock-20260712-102030-123.txt"));
        assertTrue(notes.contains("display:  3840x2160"), "the caller's environment: " + notes);
        assertTrue(notes.contains("viewport: 3840x2160+0+0"), notes);
        assertTrue(notes.contains("5 warm blob"), "what the reader saw: " + notes);
        assertTrue(notes.contains("5 plates: every fan position covered"), notes);
    }

    /**
     * The dump is written when something is already wrong. If gathering the environment is part of
     * what is wrong, that costs the report a line - never the report, and never the run.
     */
    @Test
    void anEnvironmentThatCannotBeReadStillLeavesAReadableReport(@TempDir Path dir) throws Exception {
        LiveLockView view = new LiveLockView(screen, reader, captures(dir), () -> {
            throw new IllegalStateException("no display");
        });

        Stdout.capturing(() -> view.dumpFrame("no-lock"));

        String notes = Files.readString(dir.resolve("no-lock-20260712-102030-123.txt"));
        assertTrue(notes.contains("environment: could not be read"), notes);
        assertTrue(notes.contains("5 plates: every fan position covered"),
                "and the half that matters is still there: " + notes);
    }

    private static Captures captures(Path dir) {
        return new Captures(dir, Clock.fixed(Instant.parse("2026-07-12T10:20:30.123Z"),
                ZoneOffset.UTC));
    }

    /** Crops the frame to whatever box is asked for - exactly what the live Robot hands back. */
    private static final class CroppingGrabber implements ScreenGrabber {
        private final BufferedImage frame;
        final List<Rectangle> asked = new ArrayList<>();

        CroppingGrabber(BufferedImage frame) {
            this.frame = frame;
        }

        @Override
        public BufferedImage grab(Rectangle box) {
            asked.add(new Rectangle(box));
            return frame.getSubimage(box.x, box.y, box.width, box.height);
        }
    }
}
