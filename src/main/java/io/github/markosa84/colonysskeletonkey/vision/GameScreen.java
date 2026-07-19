package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;

/**
 * The live game screen, as the tool grabs it (borderless windowed, DPI-aware {@link Robot}).
 * This class owns every pixel <i>capture</i>; making sense of the pixels is {@link LockReader}'s
 * job, and the two meet only through {@link BufferedImage}s in the game's <b>view-local</b>
 * coordinates - (0, 0) is the top-left of the game's view, wherever that view sits on the desktop.
 *
 * <p>This is the only class that knows the difference. The grabber works in virtual-desktop
 * coordinates, so every box is kept twice: once as the reader sees it, and once translated by the
 * {@link Viewport}'s origin for the grab. When the game fills the primary display the two are
 * identical, which is the case the tool shipped assuming.
 *
 * <p>The box constants below are the values <b>measured at the 4K reference resolution</b>; the
 * constructor maps them through the given {@link Viewport} (identity at
 * {@link Viewport#REFERENCE}).
 *
 * <p>Nothing here knows about the game's gamma. It used to - the lockpick counter was thresholded
 * at an absolute level, which needed the frame mapped back to the calibrated look first - and
 * {@link #pickCounterFingerprint} explains why that was exactly backwards.
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

    private final ScreenGrabber grabber;
    private final Viewport viewport;
    /** Where the lock sits <b>within the game's view</b>: the reader's coordinates, and the canvas. */
    private final Rectangle lockBox;
    /** The same box on the virtual desktop: what the grabber is actually asked for. */
    private final Rectangle lockGrab;
    private final Rectangle picksGrab;
    /** Reused view-sized canvas, so the reader's view-local coordinates keep working. */
    private BufferedImage canvas;
    private Graphics2D canvasGraphics;

    public GameScreen(Robot robot, Viewport viewport) {
        this(robot::createScreenCapture, viewport);
    }

    /** Over a {@link ScreenGrabber}: what the headless tests build, with a frame standing in. */
    GameScreen(ScreenGrabber grabber, Viewport viewport) {
        this(grabber, viewport, viewport.mapping());
    }

    /**
     * With the boxes taken from an explicit mapping rather than from the viewport's own. The
     * viewport still says <b>where the view is</b> - its origin translates every grab onto the
     * desktop, and its size is the canvas - but where the <b>lock</b> is within that view is the
     * mapping's business, and the two can disagree: a measured window rectangle is a guess, and one
     * solved from the lock's own lattice is not.
     *
     * <p>The mapping is taken once, here, so the lock grab, the picks grab and the canvas can never
     * be built from different ones. That is not a style point - the reader's coordinates, the region
     * the grab covers and the canvas it is composited onto have to agree to the pixel or the frame
     * the reader gets is stale where it matters.
     */
    GameScreen(ScreenGrabber grabber, Viewport viewport, ViewMapping mapping) {
        this.grabber = grabber;
        this.viewport = viewport;
        this.lockBox = lockBox(mapping);
        this.lockGrab = onScreen(lockBox, viewport);
        this.picksGrab = onScreen(picksBox(mapping), viewport);
    }

    /** The viewport this screen captures - the game's view, not necessarily the whole display. */
    public Viewport viewport() {
        return viewport;
    }

    /** The lock capture box for a viewport; exposed so {@code CaptureBoxTest} can prove containment. */
    static Rectangle lockBox(Viewport vp) {
        return lockBox(vp.mapping());
    }

    static Rectangle lockBox(ViewMapping mapping) {
        return box(mapping, LOCK_X0, LOCK_Y0, LOCK_W, LOCK_H);
    }

    /** The lockpick counter's box: the break detector's evidence, and {@link Tone}'s gamma probe. */
    static Rectangle picksBox(Viewport vp) {
        return picksBox(vp.mapping());
    }

    static Rectangle picksBox(ViewMapping mapping) {
        return box(mapping, PICKS_X0, PICKS_Y0, PICKS_W, PICKS_H);
    }

    /** Maps a reference box into the view: origin floored, extent ceiled, so nothing is cut. */
    private static Rectangle box(ViewMapping m, int x, int y, int w, int h) {
        return new Rectangle((int) Math.floor(m.x(x)), (int) Math.floor(m.y(y)),
                (int) Math.ceil(m.len(w)), (int) Math.ceil(m.len(h)));
    }

    /**
     * A view-local box translated onto the virtual desktop. The grabber works in desktop
     * coordinates; everything else in this project works in the game's own, which are the same
     * thing only when the game happens to fill the primary display.
     */
    private static Rectangle onScreen(Rectangle viewLocal, Viewport vp) {
        Rectangle r = new Rectangle(viewLocal);
        r.translate(vp.originX(), vp.originY());
        return r;
    }

    /** Grabs the game's whole view. Use it to save evidence; use {@link #captureLock} to poll. */
    public BufferedImage capture() {
        return grabber.grab(new Rectangle(viewport.originX(), viewport.originY(),
                viewport.width(), viewport.height()));
    }

    /**
     * Grabs only the lock's box and composites it into a reused view-sized canvas, so every
     * coordinate in {@link LockReader} still lands on the right pixel. ~3.5x faster than
     * {@link #capture}; everything outside the box is stale, so never save this image.
     */
    public BufferedImage captureLock() {
        if (canvas == null) {
            canvas = new BufferedImage(viewport.width(), viewport.height(),
                    BufferedImage.TYPE_INT_RGB);
            canvasGraphics = canvas.createGraphics();
        }
        canvasGraphics.drawImage(grabber.grab(lockGrab), lockBox.x, lockBox.y, null);
        return canvas;
    }

    /**
     * A fingerprint of the remaining-lockpicks counter. Two calls returning different values means
     * the number changed, i.e. <b>a pick broke</b>.
     *
     * <p>This is the only reliable way to see a break. Watching the plates does not work: the
     * puzzle resets only at skill level 0, so above that a break leaves the lock looking
     * untouched. Reading the digits properly would need OCR; noticing that they changed does not.
     *
     * <p><b>The threshold is measured off the panel, every grab.</b> It used to be an absolute 110,
     * applied after mapping the box through the session's {@link Tone}, and that arrangement was not
     * broken - measured over the whole gamma corpus and all three reporters' HDR dumps, it split the
     * box 37-40% dark every time, which is the digits. It was <i>lucky</i>, and in two ways worth
     * naming, because neither survives the next unmeasured display:
     * <ul>
     *   <li>The digits climb with the gamma slider - 74 at the calibration, <b>100 at 3.2</b> - so at
     *       the top of the legal range the cut had ten levels left. Nothing chose ten.</li>
     *   <li>On an HDR frame the panel sits at ink 11-36 on a white of 183-199, which is <i>off</i>
     *       the tone's one-dimensional family: the curve applied is the nearest one, which this
     *       codebase elsewhere calls worse than nothing and which {@link LatticeReader} refuses to
     *       use. It happens to land those digits near 72-75, under the cut. That the break detector
     *       kept working on those frames is a property of a curve nobody trusts.</li>
     * </ul>
     *
     * <p>So: no curve, no constant. The box carries its own two plateaus, and across every setting
     * ever measured they are never closer than <b>155 levels</b> (gamma 3.2's 100 on 255); cutting
     * at their midpoint means the same thing at every setting, on the family or off it, and needs
     * the {@link Tone} not at all. When there is no panel to measure - a box off the frame, or a
     * viewport pointing at the room - the range that <i>is</i> there is split instead, so the hash
     * stays deterministic; a wrong viewport is a real failure, but it is not this method's to
     * report, and it says so far more loudly elsewhere.
     */
    public long pickCounterFingerprint() {
        BufferedImage box = grabber.grab(picksGrab);
        int[] histogram = Tone.histogram(box);
        int split = Tone.panelSplit(histogram);
        int dark = split >= 0 ? split : midRange(histogram);
        long hash = 0xcbf29ce484222325L; // FNV-1a over the thresholded glyph pixels
        for (int y = 0; y < box.getHeight(); y++) {
            for (int x = 0; x < box.getWidth(); x++) {
                hash ^= Pixels.luminance(box.getRGB(x, y)) < dark ? 1 : 0;
                hash *= 0x100000001b3L;
            }
        }
        return hash;
    }

    /** The midpoint of whatever range the box holds: the fallback when it holds no panel. */
    private static int midRange(int[] histogram) {
        int lo = 255, hi = 0;
        for (int v = 0; v < histogram.length; v++) {
            if (histogram[v] > 0) {
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
        }
        return (lo + hi) / 2;
    }
}
