package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * The live game screen, as the tool grabs it (borderless windowed, DPI-aware {@link Robot}).
 * This class owns every pixel <i>capture</i>; making sense of the pixels is {@link LockReader}'s
 * job, and the two meet only through {@link BufferedImage}s in full-frame coordinates.
 *
 * <p>The box constants below are the values <b>measured at the 4K reference resolution</b>; the
 * constructor maps them through the given {@link Viewport} (identity at
 * {@link Viewport#REFERENCE}).
 *
 * <p>Capture, not image processing, is the cost: a full 4K {@link #capture()} is ~79ms, while
 * decoding a frame is ~8ms. Poll with {@link #captureLock()} (~23ms at 4K), never with the
 * full-screen grab.
 */
public final class GameScreen {

    /**
     * Reference (4K) screen box holding every pixel the reader reads. Grabbing this instead of the
     * whole desktop is what makes a poll ~3x cheaper than a full capture, and the capture is what
     * dominates a poll.
     *
     * <p>It must contain {@link LockReader}'s pin box and every source pixel its rotation samples,
     * for any plate count and any offsets, <b>with a generous safety belt</b> - the widest case is
     * a 7-plate lock, whose rotated crop back-projects to the screen rectangle
     * (2573, 370)-(3607, 1226), and this box clears that by at least 70px on every side.
     * {@code CaptureBoxTest} enforces the containment (belt included) for every plate count at
     * every supported viewport, so the box can never silently crop the lock.
     */
    private static final int LOCK_X0 = 2450, LOCK_Y0 = 300, LOCK_W = 1300, LOCK_H = 1120;

    /**
     * Reference (4K) box of the little white counter under the lock showing how many lockpicks are
     * left. Fixed screen position, dark digits on white - the highest-contrast thing on screen.
     */
    private static final int PICKS_X0 = 3104, PICKS_Y0 = 1616, PICKS_W = 72, PICKS_H = 56;
    /** Digits are near-black; the box behind them is near-white. Anything in between is the border. */
    private static final int PICKS_DARK_MAX = 110;

    private final Robot robot;
    private final Viewport viewport;
    private final Rectangle lockBox;
    private final Rectangle picksBox;
    /** Reused full-frame canvas, so the reader's absolute screen coordinates keep working. */
    private BufferedImage canvas;
    private Graphics2D canvasGraphics;

    public GameScreen(Robot robot, Viewport viewport) {
        this.robot = robot;
        this.viewport = viewport;
        this.lockBox = lockBox(viewport);
        this.picksBox = box(viewport, PICKS_X0, PICKS_Y0, PICKS_W, PICKS_H);
    }

    /** The lock capture box for a viewport; exposed so {@code CaptureBoxTest} can prove containment. */
    static Rectangle lockBox(Viewport vp) {
        return box(vp, LOCK_X0, LOCK_Y0, LOCK_W, LOCK_H);
    }

    /** Maps a reference box onto the viewport: origin floored, extent ceiled, so nothing is cut. */
    private static Rectangle box(Viewport vp, int x, int y, int w, int h) {
        return new Rectangle((int) Math.floor(vp.x(x)), (int) Math.floor(vp.y(y)),
                (int) Math.ceil(vp.len(w)), (int) Math.ceil(vp.len(h)));
    }

    /** Grabs a full-screen screenshot. Use it to save evidence; use {@link #captureLock} to poll. */
    public BufferedImage capture() {
        return robot.createScreenCapture(new Rectangle(0, 0, viewport.width(), viewport.height()));
    }

    /**
     * Grabs only the lock's screen box and composites it into a reused full-frame canvas, so every
     * absolute coordinate in {@link LockReader} still lands on the right pixel. ~3.5x faster than
     * {@link #capture}; everything outside the box is stale, so never save this image.
     */
    public BufferedImage captureLock() {
        if (canvas == null) {
            canvas = new BufferedImage(viewport.width(), viewport.height(),
                    BufferedImage.TYPE_INT_RGB);
            canvasGraphics = canvas.createGraphics();
        }
        canvasGraphics.drawImage(robot.createScreenCapture(lockBox), lockBox.x, lockBox.y, null);
        return canvas;
    }

    /**
     * A fingerprint of the remaining-lockpicks counter. Two calls returning different values means
     * the number changed, i.e. <b>a pick broke</b>.
     *
     * <p>This is the only reliable way to see a break. Watching the plates does not work: the
     * puzzle resets only at skill level 0, so above that a break leaves the lock looking
     * untouched. Reading the digits properly would need OCR; noticing that they changed does not.
     */
    public long pickCounterFingerprint() {
        BufferedImage box = robot.createScreenCapture(picksBox);
        long hash = 0xcbf29ce484222325L; // FNV-1a over the thresholded glyph pixels
        for (int y = 0; y < box.getHeight(); y++) {
            for (int x = 0; x < box.getWidth(); x++) {
                hash ^= LockReader.luminance(box.getRGB(x, y)) < PICKS_DARK_MAX ? 1 : 0;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }
}
