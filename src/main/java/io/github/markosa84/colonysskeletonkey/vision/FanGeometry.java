package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Where the lock is, mapped onto one {@link ViewMapping}. The <b>measured</b> half of reading a lock,
 * and the half that has never had to change: it survives four rooms, both ends of the game's gamma
 * slider, 19 display modes from 1280x720 to 4K, and an HDR tonemap that defeats every colour constant
 * in the vision layer. Every reader shares this class, so the numbers live in exactly one place.
 *
 * <p>The split is deliberate, and it is the lesson of the HDR bug. A reader has to answer two very
 * different kinds of question - <i>where is the lock</i> and <i>what colour is a pin</i> - and only
 * the second one is fragile. {@link LockReader} answers the second with absolute pixel values fitted
 * on one screen at one gamma; {@link LatticeReader} answers it with ratios instead. They disagree
 * about photometry and they agree, exactly, about geometry. That is what this class is.
 *
 * <h2>The fan</h2>
 * The minigame parks the lock at a <b>fixed screen spot</b> and draws its {@code n} plates (4-7) as a
 * receding fan. Plate {@code i} has its pin at {@code FAN_CENTER + (mid - i) * DEPTH_STEP} with
 * {@code mid = (n - 1) / 2} and {@code i = 0} the back-most plate - true whatever the plate's offset,
 * because <b>the pin never moves</b>: the plate slides underneath it.
 *
 * <h2>The rotation</h2>
 * Rotating a frame by {@link #ROT_DEG} about the fan centre lays the 7-hole rows horizontal and
 * separates the plates vertically. It is <b>measured, not guessed</b> - fitted over all 1170 hole
 * centroids of the labelled frames, where the optimum is -30.15 degrees and the safe band is about
 * -32.5..-28.3. Re-tuning it buys under a pixel; see CLAUDE.md "Rotation angle" before touching it.
 *
 * <p>No single angle can flatten every row, because the plates sit at different depths and each row
 * projects at its own angle - perspective, not curvature (the rows are straight to ~2px). So after
 * the global rotation a row's holes still climb along a line of slope {@link #rowSlope}, and blobs are
 * tested against <i>that</i> line rather than a flat one.
 */
public final class FanGeometry {

    // --- Reference (4K) values. Everything below is measured; nothing is fitted to a screen.

    /** Screen position the plate fan is centred on, and the pivot of the rotation. */
    private static final double FAN_CENTER_X = 3090.0;
    private static final double FAN_CENTER_Y = 798.0;

    /** Per-plate screen step from one plate to the next, pointing toward the back of the fan. */
    private static final double DEPTH_STEP_X = 58.9;
    private static final double DEPTH_STEP_Y = -36.75;

    /** Screen box holding every 4-7 plate pin (the fan plus a little margin). */
    private static final int RX0 = 2650, RY0 = 620, RX1 = 3520, RY1 = 1000;

    /**
     * Rotation (deg, about the fan centre) that lays the hole rows horizontal. Angles are unaffected
     * by uniform scaling, so this needs no viewport mapping.
     */
    static final double ROT_DEG = -30.0;

    /**
     * Residual tilt of each row against the single global rotation: a plate's own optimal angle is
     * {@code OPT_BASE_DEG + OPT_PER_DEPTH_DEG * depth} (depth in fan steps, positive toward the back).
     * Measured over the same 1170-hole census as {@link #ROT_DEG}.
     */
    private static final double OPT_BASE_DEG = -29.80;
    private static final double OPT_PER_DEPTH_DEG = 0.70;

    /**
     * A blob belongs to a row if it is within {@code ROW_MAX_DY} px of the row's <b>deskewed</b> line
     * and {@code ROW_MAX_DX} px of the row's pin. Real holes sit within ~5px of their line; the
     * arch-gap shadow that once broke a 6-plate read sat 24px off it. The x bound is deliberately
     * generous - the widest row measured reaches 304px, and a 7-plate lock's front plate sits one fan
     * step nearer the camera.
     */
    private static final int ROW_MAX_DY = 12;
    private static final int ROW_MAX_DX = 345;

    /** Extra margin around the rotated fan crop, beyond the row bounds above. */
    private static final int CROP_PAD_X = 25;
    private static final int CROP_PAD_Y = 28;

    /**
     * Distance between adjacent holes along a row. Perspective makes it vary with plate depth and
     * position in the row (measured 41-54), so a reader accepts a window rather than one value.
     */
    private static final double STEP_MIN = 36, STEP_MAX = 62, STEP_IDEAL = 48;

    /**
     * A double hole spacing, which {@link #walk} accepts to repair a single undetected hole mid-row.
     * The single-step window itself is {@link #STEP_MIN}/{@link #STEP_MAX}/{@link #STEP_IDEAL}.
     */
    private static final double SKIP_MIN = 80, SKIP_MAX = 118, SKIP_IDEAL = 96;

    /** Accepted hole blob geometry. Measured 15-42 wide, 14-34 tall, 150-950 px in area. */
    private static final int HOLE_MIN_W = 15, HOLE_MAX_W = 42;
    private static final int HOLE_MIN_H = 14, HOLE_MAX_H = 34;
    private static final int HOLE_MIN_AREA = 150, HOLE_MAX_AREA = 950;

    /** Dark holes per plate: the 7 holes minus the one the pin occupies. */
    public static final int HOLES_PER_PLATE = 2 * LockModel.MAX_OFFSET;

    // --- The same quantities, mapped onto the actual view.

    private final ViewMapping mapping;
    final double fanCenterX, fanCenterY;
    final double depthStepX, depthStepY;
    final int rx0, ry0, rx1, ry1;
    final int rowMaxDx, rowMaxDy;
    final int cropPadX, cropPadY;
    final double stepMin, stepMax, stepIdeal;
    final double skipMin, skipMax, skipIdeal;
    final double holeMinW, holeMaxW, holeMinH, holeMaxH;
    final double holeMinArea, holeMaxArea;

    /** The fan as it lands in a game view of this size - the measured way to know where the lock is. */
    public FanGeometry(Viewport viewport) {
        this(viewport.mapping());
    }

    /**
     * The fan under an arbitrary mapping - including one that was solved for from the lock's own
     * lattice rather than derived from a measured window rectangle. There is no window in a mapping,
     * and this class never needed one: it only ever asked the viewport where things land.
     */
    public FanGeometry(ViewMapping mapping) {
        this.mapping = mapping;
        fanCenterX = mapping.x(FAN_CENTER_X);
        fanCenterY = mapping.y(FAN_CENTER_Y);
        depthStepX = mapping.len(DEPTH_STEP_X);
        depthStepY = mapping.len(DEPTH_STEP_Y);
        rx0 = (int) Math.round(mapping.x(RX0));
        ry0 = (int) Math.round(mapping.y(RY0));
        rx1 = (int) Math.round(mapping.x(RX1));
        ry1 = (int) Math.round(mapping.y(RY1));
        // Floored at 4px: the effective gate the old flat 20px bound already had at 800x600, so
        // blob-centroid noise at tiny resolutions keeps the room it has always had.
        rowMaxDy = Math.max(4, (int) Math.round(mapping.len(ROW_MAX_DY)));
        rowMaxDx = (int) Math.round(mapping.len(ROW_MAX_DX));
        cropPadX = (int) Math.round(mapping.len(CROP_PAD_X));
        cropPadY = (int) Math.round(mapping.len(CROP_PAD_Y));
        stepMin = mapping.len(STEP_MIN);
        stepMax = mapping.len(STEP_MAX);
        stepIdeal = mapping.len(STEP_IDEAL);
        skipMin = mapping.len(SKIP_MIN);
        skipMax = mapping.len(SKIP_MAX);
        skipIdeal = mapping.len(SKIP_IDEAL);
        holeMinW = mapping.len(HOLE_MIN_W);
        holeMaxW = mapping.len(HOLE_MAX_W);
        holeMinH = mapping.len(HOLE_MIN_H);
        holeMaxH = mapping.len(HOLE_MAX_H);
        holeMinArea = mapping.area(HOLE_MIN_AREA);
        holeMaxArea = mapping.area(HOLE_MAX_AREA);
    }

    /** Where this fan's reference coordinates land. */
    public ViewMapping mapping() {
        return mapping;
    }

    /** Expected pin position of plate {@code i} in an {@code n}-plate fan (offset-independent). */
    public double[] pinPosition(int n, int i) {
        double mid = (n - 1) / 2.0;
        return new double[] {fanCenterX + (mid - i) * depthStepX,
                             fanCenterY + (mid - i) * depthStepY};
    }

    /** The pin-detection scan box, for {@code CaptureBoxTest}'s containment proof. */
    Rectangle pinBox() {
        return new Rectangle(rx0, ry0, rx1 - rx0, ry1 - ry0);
    }

    /** Rotates a point by {@code deg} about the fan centre. */
    double[] rotatePoint(double px, double py, double deg) {
        double t = Math.toRadians(deg), c = Math.cos(t), s = Math.sin(t);
        double x = px - fanCenterX, y = py - fanCenterY;
        return new double[] {fanCenterX + x * c - y * s, fanCenterY + x * s + y * c};
    }

    /** Plate {@code i}'s pin, in the rotated frame - where its hole row runs horizontally. */
    double[] rowPin(int n, int i) {
        return rowPinAtDepth((n - 1) / 2.0 - i);
    }

    /**
     * The pin of a plate sitting {@code depth} fan steps back from the centre, in the rotated frame.
     *
     * <p>Depth is the honest coordinate here, and plate indices are not: plate {@code i} of {@code n}
     * sits at {@code (n-1)/2 - i}, so an odd plate count puts its plates on whole steps and an <b>even
     * one puts them on half steps</b>. A 6-plate lock's plates therefore sit exactly <i>between</i> a
     * 7-plate lock's. Anything that wants to sample "the plates" without knowing the count yet - which
     * is every photometric measurement in {@link LatticeReader}, because it has to measure the steel
     * before it can count anything - has to sample every half step, or it lands in the gaps.
     */
    double[] rowPinAtDepth(double depth) {
        double x = fanCenterX + depth * depthStepX;
        double y = fanCenterY + depth * depthStepY;
        return rotatePoint(x, y, ROT_DEG);
    }

    /** Residual slope of the row at {@code depth} fan steps, in the rotated frame. */
    static double slopeAtDepth(double depth) {
        return Math.tan(Math.toRadians(ROT_DEG - (OPT_BASE_DEG + OPT_PER_DEPTH_DEG * depth)));
    }

    /**
     * Residual slope of row {@code i}'s line in the rotated frame. After the global rotation each
     * row still climbs, by exactly as much as its depth in the fan says it should.
     */
    static double rowSlope(int n, int i) {
        return slopeAtDepth((n - 1) / 2.0 - i);
    }

    /** Every depth a plate of any supported lock can sit at: whole steps, and the half steps between. */
    static double[] plateDepths() {
        double[] out = new double[2 * 2 * LockModel.MAX_OFFSET + 1]; // -3, -2.5 ... 3
        for (int i = 0; i < out.length; i++) {
            out[i] = LockModel.MAX_OFFSET - i * 0.5;
        }
        return out;
    }

    /**
     * Walks the hole lattice outward from a pin at {@code px} in direction {@code dir}, hopping from
     * hole to hole one spacing at a time, and returns how many hole slots it crossed (out of six).
     *
     * <p>A walk rather than a rigid lattice fit on purpose: perspective makes the spacing vary
     * 41-54px along a single row, so no fixed lattice fits all seven slots at once - its slots drift
     * off the holes by the third step. Stepping hole to hole lets the row bend as the camera bends
     * it. Blobs that do not sit a plausible step from the previous one are ignored, which is what
     * rejects shadows. With {@code allowSkip} a double step is also accepted, bridging a single
     * undetected hole - callers ask for that only after the exact walk failed to add up to six,
     * because on a fully detected row a skip can only overshoot and "bridge" a hole that was never
     * missing. (An arch-gap shadow 2.25 spacings past the end of a row once read a healthy 6-plate
     * lock UNKNOWN exactly that way; see {@code 6p-gap-shadow} in the test frames.)
     *
     * <p>Both readers share this; the numbers it walks on ({@link #stepMin}/{@link #stepMax} and the
     * skip window) are measured geometry, so they live here with the rest of the fan.
     */
    int walk(List<Double> rowHoles, double px, int dir, boolean allowSkip) {
        double cur = px;
        int slots = 0;
        while (slots < HOLES_PER_PLATE) {
            double bestX = 0, bestErr = Double.MAX_VALUE;
            int bestSlots = 0;
            for (double x : rowHoles) {
                double d = (x - cur) * dir;
                int step = (d >= stepMin && d <= stepMax) ? 1
                        : (allowSkip && d >= skipMin && d <= skipMax) ? 2 : 0;
                if (step == 0) {
                    continue;
                }
                double err = Math.abs(d - (step == 1 ? stepIdeal : skipIdeal));
                if (err < bestErr) {
                    bestErr = err;
                    bestX = x;
                    bestSlots = step;
                }
            }
            if (bestSlots == 0) {
                break;
            }
            slots += bestSlots;
            cur = bestX;
        }
        return slots;
    }

    /**
     * The rotated-frame crop holding every hole row of an {@code n}-plate fan, as
     * {@code {x0, y0, x1, y1}}. Rows run left-to-right, and plate 0 (the back one) rotates to the top.
     * It is sized from the row gates, which already span the whole track, so it depends only on the
     * plate count and <b>never on the offsets</b>.
     */
    int[] fanCrop(int n) {
        double[] back = rowPin(n, 0);
        double[] front = rowPin(n, n - 1);
        return new int[] {
            (int) front[0] - rowMaxDx - cropPadX,
            (int) back[1] - rowMaxDy - cropPadY,
            (int) back[0] + rowMaxDx + cropPadX,
            (int) front[1] + rowMaxDy + cropPadY,
        };
    }

    /**
     * The screen-space bounding box of every source pixel a reader samples for an {@code n}-plate
     * lock: {@link #fanCrop} back-projected. {@code CaptureBoxTest} asserts that {@link GameScreen}'s
     * capture box contains this (plus a safety belt) at every viewport - so the two must be the same
     * rectangle, hence the one method.
     */
    Rectangle fanCropScreenBounds(int n) {
        int[] crop = fanCrop(n);
        int x0 = crop[0], y0 = crop[1], x1 = crop[2], y1 = crop[3];
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] corner : new double[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            double[] screen = rotatePoint(corner[0], corner[1], -ROT_DEG);
            minX = Math.min(minX, screen[0]);
            maxX = Math.max(maxX, screen[0]);
            minY = Math.min(minY, screen[1]);
            maxY = Math.max(maxY, screen[1]);
        }
        return new Rectangle((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY));
    }

    /**
     * Rotates the frame by {@link #ROT_DEG} about the fan centre and returns only the {@code w x h}
     * crop whose top-left is rotated-frame {@code (x0, y0)} (bilinear).
     */
    BufferedImage rotateFan(BufferedImage src, int x0, int y0, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(-x0, -y0);
        g.rotate(Math.toRadians(ROT_DEG), fanCenterX, fanCenterY);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return o;
    }
}
