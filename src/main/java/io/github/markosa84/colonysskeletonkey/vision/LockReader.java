package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Reads the lock state out of a frame of the game. Pure image analysis: every method takes a
 * {@link BufferedImage} in full-frame coordinates and touches nothing else - capturing frames is
 * {@link GameScreen}'s job, which is what keeps this class usable headless against PNG files (see
 * {@code LockReaderTest}).
 *
 * <p>Every pixel constant below is the value <b>measured at the 4K reference resolution</b>; the
 * constructor maps them through the given {@link Viewport} - positions, distances and blob
 * dimensions linearly, blob areas quadratically, luminance/color thresholds not at all. At
 * {@link Viewport#REFERENCE} the mapping is exactly the identity, so the 34-frame gate vouches
 * for the parameterization itself; other resolutions are geometrically sound but not validated
 * until labelled frames exist for them (see CLAUDE.md).
 *
 * <h2>How the lock actually works (reverse-engineered from the labelled frames)</h2>
 * The minigame renders the lock as a 3D fan of {@code n} stacked plates (4-7). Each plate has a row
 * of <b>7 holes</b> and one brass <b>pin</b>; the lock opens when every plate is slid so its pin
 * sits in the <b>middle</b> hole (offset 0), giving the range [-3, +3] ({@link LockModel#MAX_OFFSET}).
 *
 * <p>Three facts, measured from the screenshots (including the labelled slide sequences in
 * {@code src/test/data/frames/}), drive the reader:
 * <ol>
 *   <li><b>Fixed framing.</b> The minigame always parks the lock at the same screen spot: the pins
 *       sit on a fan about {@code FAN_CENTER}, one {@code DEPTH_STEP} apart (back to front). The
 *       predicted pin positions match every example to ~2px, across four different rooms.</li>
 *   <li><b>The pin does not move with offset.</b> Sliding a plate moves the plate and its holes;
 *       the pin stays at its plate's fixed screen position. So pin <i>position</i> carries the
 *       plate count, not the offset.</li>
 *   <li><b>The pin "pops" only when centred.</b> At offset 0 the pin stands up as a tall,
 *       pink-topped cylinder (warm blob ~380-450px at 4K); at any nonzero offset it lies flat
 *       (~150-180px). This is the game's own feedback, and it is exact - but binary: it does
 *       <i>not</i> grade with magnitude (offset -1, -2 and -3 all look the same), so the pop alone
 *       tells you "centred or not", never how far off a plate is.</li>
 * </ol>
 *
 * <p>For an off-centre plate the magnitude is read by <b>counting holes</b>: rotating the frame by
 * {@link #ROT_DEG} about {@code FAN_CENTER} lays each plate's 7-hole row horizontal and separates
 * the plates vertically. Six of the seven holes are then dark blobs (the seventh holds the pin), so
 * a plate's offset is simply how many of them lie left of its pin, minus {@link LockModel#MAX_OFFSET}.
 * {@link #readState} finds the blobs, assigns them to rows, and walks outward from each pin one
 * hole-spacing at a time; centred plates are answered by the pop signal instead.
 *
 * <p>Three details make the hole test robust. <b>No single rotation lays every row flat</b> - the
 * plates sit at different depths in the fan, so each row projects at a slightly different screen
 * angle (the rows themselves are straight to ~2px; see CLAUDE.md "Rotation angle"). Blobs are
 * therefore found in 2D and tested against each row's own <b>deskewed line</b> ({@link #rowSlope}),
 * rather than sampled along one straight line. The plates also carry dark shadows that look
 * hole-shaped - the bent end-tab, the gap between two plates' arches: most show lit metal and fail
 * the near-black test ({@code minLum <= }{@link #HOLE_MAX_MIN_LUM}), but a gap can show the room's
 * own darkness and pass every pixel gate. Which is why, finally, {@link #walk} demands the row add
 * up to <b>exactly six</b> holes, taking single spacings first and a bridging double step only when
 * a hole went undetected.
 *
 * <p><b>Calibration status:</b> plate count, centred detection and offsets all validate on every
 * labelled frame in {@code src/test/data/frames/} - the five explicit offsets of
 * {@code 5p-plates-1-2-opposed/step-0}, and the frames of four slide sequences (5- and 6-plate
 * locks) where each frame differs from the last by exactly one plate step. See {@code LockReaderTest}.
 */
public final class LockReader {

    /** Offset returned when a plate's hole row could not be read (see {@link #readState}). */
    public static final int UNKNOWN = LockModel.UNKNOWN;

    /** Dark holes per plate: the 7 holes minus the one the pin occupies. */
    private static final int HOLES_PER_PLATE = 2 * LockModel.MAX_OFFSET;

    // --- Reference (4K) lock geometry, calibrated from the labelled frames (4 rooms, 4/5/6
    // --- plates). These are the measured values; the instance fields below are the same
    // --- quantities mapped onto the actual viewport.

    /**
     * Screen position the plate fan is centred on. Plate {@code i} of {@code n} has its pin at
     * {@code FAN_CENTER + (mid - i) * DEPTH_STEP}, with {@code mid = (n - 1) / 2} and {@code i = 0}
     * the back-most plate. This holds regardless of any plate's offset (the pin never moves).
     */
    private static final double FAN_CENTER_X = 3090.0;
    private static final double FAN_CENTER_Y = 798.0;

    /** Per-plate screen step from one plate to the next, pointing toward the back of the fan. */
    private static final double DEPTH_STEP_X = 58.9;
    private static final double DEPTH_STEP_Y = -36.75;

    // --- Pin detection ---

    /** Screen box holding every 4-7 plate pin (the fan plus a little margin). */
    private static final int RX0 = 2650, RY0 = 620, RX1 = 3520, RY1 = 1000;
    /** Merge radius for warm pixels into one pin blob, and the accepted blob pixel-count window. */
    private static final int CLUSTER_RADIUS = 24;
    private static final int PIN_MIN_PIXELS = 45;
    private static final int PIN_MAX_PIXELS = 700;
    /** A pin blob at least this big means the plate is centred (pin popped up). Off-centre pins
     *  measured ~150-180px, centred ~380-450px; 250 sits safely between. Tunable. */
    private static final int CENTERED_MIN_PIXELS = 250;
    /** Max distance (px) a detected pin may sit from a plate's fan position to match it. */
    private static final double MATCH_MAX_DIST = 26.0;

    // --- Off-centre offset reading (rotate so hole rows are horizontal, then count dark holes) ---

    /**
     * Rotation (deg, about FAN_CENTER) that lays the hole rows horizontal and separates the plates.
     * <b>Measured, not guessed</b>, by fitting all 1170 hole centroids in the labelled frames: the
     * optimum is -30.15 deg and the safe band is about -32.5..-28.3, so -30 sits near its middle.
     * Re-tuning it buys under a pixel. Per-plate optima span -28.26..-31.66 (perspective), which is
     * why one angle cannot flatten every row. See CLAUDE.md "Rotation angle" before touching this.
     * Angles are unaffected by uniform scaling, so this needs no viewport mapping.
     */
    private static final double ROT_DEG = -30.0;

    /**
     * Residual tilt of each row against the single global rotation, measured over the same
     * 1170-hole census as {@link #ROT_DEG}: a plate's own optimal angle is
     * {@code OPT_BASE_DEG + OPT_PER_DEPTH_DEG * depth} (depth in fan steps, positive toward the
     * back), so after rotating by {@code ROT_DEG} a row's holes still climb along a line of slope
     * {@code tan(ROT_DEG - opt(depth))} through its pin. Testing blobs against that line instead
     * of a flat one keeps real holes within ~5px (flat: 11px at 4K, and an extrapolated 16.5px on
     * a 7-plate lock's end rows), which is what lets {@link #ROW_MAX_DY} sit tight enough to
     * reject gap shadows. Angles survive uniform scaling: no viewport mapping. See CLAUDE.md
     * "Rotation angle".
     */
    private static final double OPT_BASE_DEG = -29.80;
    private static final double OPT_PER_DEPTH_DEG = 0.70;

    /** Luminance below which a pixel is a hole candidate. */
    private static final int HOLE_DARK_MAX = 105;
    /** Accepted hole blob geometry (px). Measured 15-42 wide, 14-34 tall, 150-950 px area. */
    private static final int HOLE_MIN_AREA = 150, HOLE_MAX_AREA = 950;
    private static final int HOLE_MIN_W = 15, HOLE_MAX_W = 42;
    private static final int HOLE_MIN_H = 14, HOLE_MAX_H = 34;
    /**
     * A real hole shows the near-black void behind the plate; the plate's own shadows (in the bent
     * end-tab, or the inter-plate gap) bottom out around 76. Measured: holes 0-50, shadows 76-96.
     * <b>That separation is room-dependent</b>: in the difficulty-4 chest's room an arch-gap
     * shadow bottomed at 57 while a real hole backed by the plate behind read 45, so this gate
     * cannot tell those two apart - the deskewed row line and the exact walk are what reject such
     * a shadow (see {@code 6p-gap-shadow} in the test frames). Luminance, not geometry: never
     * scaled. (At low resolutions downscaled antialiasing may narrow the margin - re-measure
     * there rather than widening this blindly.)
     */
    private static final int HOLE_MAX_MIN_LUM = 62;
    /** Second guard on the same distinction. Measured: holes 19-82, shadows 92-96. */
    private static final int HOLE_MAX_MEAN_LUM = 88;

    /**
     * A blob belongs to a row if it is within this many px of the row's <b>deskewed line</b> in y
     * (see {@link #rowSlope}) and of the row's pin in x. The y bound is deliberately tight: real
     * holes sit within ~5px of their line, while the arch-gap shadow that once broke a 6-plate
     * read sat 24px off it. (It scales with the viewport but floors at 4px, the effective value
     * the old flat 20px gate had at 800x600, so blob-centroid noise at tiny resolutions keeps its
     * old room.) The x bound has headroom: the widest row measured (a 6-plate lock's front plate)
     * reaches 304px, and a 7-plate lock's front plate sits one fan step nearer the camera. Being
     * generous in x is safe - {@link #walk} discards anything off the hole lattice anyway.
     */
    private static final int ROW_MAX_DY = 12;
    private static final int ROW_MAX_DX = 345;

    /**
     * Extra margin around the rotated fan crop, beyond the row bounds above. The y pad also
     * absorbs the deskewed lines' wander (up to ~12px at the far ends of the extreme rows), so
     * 12 + 28 keeps the exact crop the old flat 20 + 20 had.
     */
    private static final int CROP_PAD_X = 25;
    private static final int CROP_PAD_Y = 28;

    /**
     * Distance (px) between adjacent holes along a row. Perspective makes it vary with plate depth
     * and position in the row (measured 41-54), so the walk accepts a window rather than one value.
     * {@code SKIP_*} accepts a double step, repairing a single undetected hole mid-row.
     */
    private static final double STEP_MIN = 36, STEP_MAX = 62, STEP_IDEAL = 48;
    private static final double SKIP_MIN = 80, SKIP_MAX = 118, SKIP_IDEAL = 96;

    // --- The same quantities, mapped onto the actual viewport ---

    private final double fanCenterX, fanCenterY;
    private final double depthStepX, depthStepY;
    private final int rx0, ry0, rx1, ry1;
    private final double clusterRadius;
    private final double pinMinPixels, pinMaxPixels;
    private final double centeredMinPixels;
    private final double matchMaxDist;
    private final double holeMinArea, holeMaxArea;
    private final double holeMinW, holeMaxW, holeMinH, holeMaxH;
    private final int rowMaxDy, rowMaxDx;
    private final int cropPadX, cropPadY;
    private final double stepMin, stepMax, stepIdeal;
    private final double skipMin, skipMax, skipIdeal;

    public LockReader(Viewport viewport) {
        fanCenterX = viewport.x(FAN_CENTER_X);
        fanCenterY = viewport.y(FAN_CENTER_Y);
        depthStepX = viewport.len(DEPTH_STEP_X);
        depthStepY = viewport.len(DEPTH_STEP_Y);
        rx0 = (int) Math.round(viewport.x(RX0));
        ry0 = (int) Math.round(viewport.y(RY0));
        rx1 = (int) Math.round(viewport.x(RX1));
        ry1 = (int) Math.round(viewport.y(RY1));
        clusterRadius = viewport.len(CLUSTER_RADIUS);
        pinMinPixels = viewport.area(PIN_MIN_PIXELS);
        pinMaxPixels = viewport.area(PIN_MAX_PIXELS);
        centeredMinPixels = viewport.area(CENTERED_MIN_PIXELS);
        matchMaxDist = viewport.len(MATCH_MAX_DIST);
        holeMinArea = viewport.area(HOLE_MIN_AREA);
        holeMaxArea = viewport.area(HOLE_MAX_AREA);
        holeMinW = viewport.len(HOLE_MIN_W);
        holeMaxW = viewport.len(HOLE_MAX_W);
        holeMinH = viewport.len(HOLE_MIN_H);
        holeMaxH = viewport.len(HOLE_MAX_H);
        rowMaxDy = Math.max(4, (int) Math.round(viewport.len(ROW_MAX_DY)));
        rowMaxDx = (int) Math.round(viewport.len(ROW_MAX_DX));
        cropPadX = (int) Math.round(viewport.len(CROP_PAD_X));
        cropPadY = (int) Math.round(viewport.len(CROP_PAD_Y));
        stepMin = viewport.len(STEP_MIN);
        stepMax = viewport.len(STEP_MAX);
        stepIdeal = viewport.len(STEP_IDEAL);
        skipMin = viewport.len(SKIP_MIN);
        skipMax = viewport.len(SKIP_MAX);
        skipIdeal = viewport.len(SKIP_IDEAL);
    }

    /** A detected pin: screen centroid and blob size (px); the pop threshold lives in the reader. */
    record Pin(double x, double y, int size) {}

    /** Expected pin position of plate {@code i} in an {@code n}-plate fan (offset-independent). */
    double[] pinPosition(int n, int i) {
        double mid = (n - 1) / 2.0;
        return new double[] {fanCenterX + (mid - i) * depthStepX,
                             fanCenterY + (mid - i) * depthStepY};
    }

    /** The pin-detection scan box, for {@code CaptureBoxTest}'s containment proof. */
    java.awt.Rectangle pinBox() {
        return new java.awt.Rectangle(rx0, ry0, rx1 - rx0, ry1 - ry0);
    }

    /**
     * The screen-space bounding box of every source pixel {@link #readState} samples for an
     * {@code n}-plate lock: the rotated fan crop, computed exactly as {@code readState} computes
     * it, back-projected to the screen. It depends only on the plate count, never on offsets -
     * the crop is sized from the row gates, which already span the whole track - so containment
     * proven for a plate count holds for any lock state. {@code CaptureBoxTest} asserts that
     * {@link GameScreen}'s capture box contains this (plus a safety belt) at every viewport.
     */
    java.awt.Rectangle fanCropScreenBounds(int n) {
        double[][] rowPin = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] pin = pinPosition(n, i);
            rowPin[i] = rotatePoint(pin[0], pin[1], ROT_DEG);
        }
        int x0 = (int) rowPin[n - 1][0] - rowMaxDx - cropPadX;
        int x1 = (int) rowPin[0][0] + rowMaxDx + cropPadX;
        int y0 = (int) rowPin[0][1] - rowMaxDy - cropPadY;
        int y1 = (int) rowPin[n - 1][1] + rowMaxDy + cropPadY;
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] corner : new double[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
            double[] screen = rotatePoint(corner[0], corner[1], -ROT_DEG);
            minX = Math.min(minX, screen[0]);
            maxX = Math.max(maxX, screen[0]);
            minY = Math.min(minY, screen[1]);
            maxY = Math.max(maxY, screen[1]);
        }
        return new java.awt.Rectangle((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.ceil(maxX - minX), (int) Math.ceil(maxY - minY));
    }

    /**
     * Detects the plate count by finding the brass pins. Since pins sit at fixed fan positions, it
     * returns the largest {@code n} (4-7) whose every fan position is covered by a pin - stray warm
     * blobs in the box are simply left unmatched. Returns -1 if no fan fits. Works at any offsets.
     */
    public int detectPlateCount(BufferedImage img) {
        List<Pin> pins = detectPins(img);
        for (int n = LockModel.MAX_PLATES; n >= LockModel.MIN_PLATES; n--) {
            if (allPositionsCovered(pins, n)) return n;
        }
        return -1;
    }

    /**
     * Per-plate centred flag for an {@code n}-plate lock: true where the plate's pin has popped up
     * (offset 0). A plate whose pin is missing reads false. This is the robust single-frame signal.
     */
    public boolean[] readCentered(BufferedImage img, int n) {
        List<Pin> pins = detectPins(img);
        boolean[] out = new boolean[n];
        boolean[] used = new boolean[pins.size()];
        for (int i = 0; i < n; i++) {
            int j = matchPin(pins, used, n, i);
            out[i] = j >= 0 && pins.get(j).size() >= centeredMinPixels;
            if (j >= 0) used[j] = true;
        }
        return out;
    }

    /**
     * Reads every plate's offset for a lock known to have {@code n} plates: 0 where the pin has
     * popped, otherwise (holes left of the pin) - {@link LockModel#MAX_OFFSET}. A plate whose row
     * does not yield exactly {@link #HOLES_PER_PLATE} holes reads {@link #UNKNOWN}; see
     * {@link LockModel#isComplete}.
     */
    public int[] readState(BufferedImage img, int n) {
        boolean[] centered = readCentered(img, n);

        double[][] rowPin = new double[n][];
        for (int i = 0; i < n; i++) {
            double[] pin = pinPosition(n, i);
            rowPin[i] = rotatePoint(pin[0], pin[1], ROT_DEG);
        }
        // Rows run left-to-right, and plate 0 (back) rotates to the top of the fan.
        int x0 = (int) rowPin[n - 1][0] - rowMaxDx - cropPadX;
        int x1 = (int) rowPin[0][0] + rowMaxDx + cropPadX;
        int y0 = (int) rowPin[0][1] - rowMaxDy - cropPadY;
        int y1 = (int) rowPin[n - 1][1] + rowMaxDy + cropPadY;
        List<double[]> holes = detectHoles(rotateFan(img, x0, y0, x1 - x0, y1 - y0), x0, y0);

        List<List<Double>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new ArrayList<>());
        for (double[] h : holes) {
            int row = nearestRow(rowPin, h[1]);
            double lineY = rowPin[row][1] + (h[0] - rowPin[row][0]) * rowSlope(n, row);
            if (Math.abs(h[1] - lineY) <= rowMaxDy
                    && Math.abs(h[0] - rowPin[row][0]) <= rowMaxDx) {
                rows.get(row).add(h[0]);
            }
        }

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            if (centered[i]) {
                out[i] = 0; // pop signal is exact; don't second-guess it
                continue;
            }
            int left = walk(rows.get(i), rowPin[i][0], -1, false);
            int right = walk(rows.get(i), rowPin[i][0], +1, false);
            if (left + right != HOLES_PER_PLATE) {
                left = walk(rows.get(i), rowPin[i][0], -1, true);
                right = walk(rows.get(i), rowPin[i][0], +1, true);
            }
            out[i] = (left + right == HOLES_PER_PLATE) ? left - LockModel.MAX_OFFSET : UNKNOWN;
        }
        return out;
    }

    /** Residual slope of row {@code i}'s deskewed line in the rotated frame; see {@link #OPT_BASE_DEG}. */
    private static double rowSlope(int n, int i) {
        double depth = (n - 1) / 2.0 - i;
        return Math.tan(Math.toRadians(ROT_DEG - (OPT_BASE_DEG + OPT_PER_DEPTH_DEG * depth)));
    }

    /** Index of the row whose (rotated) pin is closest in y to {@code y}. */
    private static int nearestRow(double[][] rowPin, double y) {
        int best = 0;
        for (int i = 1; i < rowPin.length; i++) {
            if (Math.abs(y - rowPin[i][1]) < Math.abs(y - rowPin[best][1])) best = i;
        }
        return best;
    }

    /**
     * Walks the hole lattice outward from a pin at {@code px} in direction {@code dir}, hopping from
     * hole to hole one spacing at a time, and returns how many hole slots it crossed. Blobs that do
     * not sit a plausible step away from the previous one are ignored, which is what rejects
     * shadows. With {@code allowSkip} a double step is also accepted, bridging a single undetected
     * hole - {@link #readState} asks for that only after the exact walk failed to add up, because
     * on a fully detected row a skip can only overshoot: it "bridges" a hole that was never
     * missing. (An arch-gap shadow 2.25 spacings past the end of a row once read a healthy 6-plate
     * lock UNKNOWN exactly that way; see {@code 6p-gap-shadow} in the test frames.)
     */
    private int walk(List<Double> rowHoles, double px, int dir, boolean allowSkip) {
        double cur = px;
        int slots = 0;
        while (slots < HOLES_PER_PLATE) {
            double bestX = 0, bestErr = Double.MAX_VALUE;
            int bestSlots = 0;
            for (double x : rowHoles) {
                double d = (x - cur) * dir;
                int step = (d >= stepMin && d <= stepMax) ? 1
                        : (allowSkip && d >= skipMin && d <= skipMax) ? 2 : 0;
                if (step == 0) continue;
                double err = Math.abs(d - (step == 1 ? stepIdeal : skipIdeal));
                if (err < bestErr) {
                    bestErr = err;
                    bestX = x;
                    bestSlots = step;
                }
            }
            if (bestSlots == 0) break;
            slots += bestSlots;
            cur = bestX;
        }
        return slots;
    }

    /**
     * Connected dark blobs in a rotated fan crop that pass the hole tests (size, shape and a
     * near-black interior). Returns their centroids in rotated-frame coordinates, as {@code {x, y}}.
     */
    private List<double[]> detectHoles(BufferedImage crop, int x0, int y0) {
        int w = crop.getWidth(), h = crop.getHeight();
        boolean[] dark = new boolean[w * h];
        int[] lum = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = luminance(crop.getRGB(x, y));
                lum[y * w + x] = l;
                dark[y * w + x] = l < HOLE_DARK_MAX;
            }
        }
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        List<double[]> out = new ArrayList<>();
        for (int seed = 0; seed < w * h; seed++) {
            if (!dark[seed] || seen[seed]) continue;
            int sp = 0;
            stack[sp++] = seed;
            seen[seed] = true;
            int area = 0, minX = w, maxX = -1, minY = h, maxY = -1, minLum = 255;
            long sumX = 0, sumY = 0, sumLum = 0;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w, y = p / w;
                area++;
                sumX += x;
                sumY += y;
                sumLum += lum[p];
                minLum = Math.min(minLum, lum[p]);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int q = ny * w + nx;
                        if (dark[q] && !seen[q]) {
                            seen[q] = true;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            if (area < holeMinArea || area > holeMaxArea
                    || bw < holeMinW || bw > holeMaxW || bh < holeMinH || bh > holeMaxH
                    || minLum > HOLE_MAX_MIN_LUM || sumLum / area > HOLE_MAX_MEAN_LUM) {
                continue;
            }
            out.add(new double[] {x0 + (double) sumX / area, y0 + (double) sumY / area});
        }
        return out;
    }

    /**
     * Rotates the frame by {@link #ROT_DEG} about the fan centre and returns only the {@code w x h}
     * crop whose top-left is rotated-frame {@code (x0, y0)} (bilinear).
     */
    private BufferedImage rotateFan(BufferedImage src, int x0, int y0, int w, int h) {
        BufferedImage o = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.translate(-x0, -y0);
        g.rotate(Math.toRadians(ROT_DEG), fanCenterX, fanCenterY);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return o;
    }

    /** Rotates a point by {@code deg} about the fan centre. */
    private double[] rotatePoint(double px, double py, double deg) {
        double t = Math.toRadians(deg), c = Math.cos(t), s = Math.sin(t);
        double x = px - fanCenterX, y = py - fanCenterY;
        return new double[] {fanCenterX + x * c - y * s, fanCenterY + x * s + y * c};
    }

    /** Index of the unused detected pin closest to plate {@code i}'s fan position, or -1. */
    private int matchPin(List<Pin> pins, boolean[] used, int n, int i) {
        double[] c = pinPosition(n, i);
        int best = -1;
        double bestD = matchMaxDist;
        for (int j = 0; j < pins.size(); j++) {
            if (used[j]) continue;
            double d = Math.hypot(pins.get(j).x() - c[0], pins.get(j).y() - c[1]);
            if (d < bestD) {
                bestD = d;
                best = j;
            }
        }
        return best;
    }

    /** True if every plate position of an {@code n}-plate fan has a distinct pin nearby. */
    private boolean allPositionsCovered(List<Pin> pins, int n) {
        boolean[] used = new boolean[pins.size()];
        for (int i = 0; i < n; i++) {
            int j = matchPin(pins, used, n, i);
            if (j < 0) return false;
            used[j] = true;
        }
        return true;
    }

    /**
     * Finds the brass pins inside the lock's screen box: collect warm pixels, merge into blobs, keep
     * blobs of pin size. Both flat (off-centre) and popped-up (centred) pins are warm brass.
     */
    List<Pin> detectPins(BufferedImage img) {
        // A correctly mapped viewport keeps the box inside the frame; the clamp makes a
        // mis-assumed aspect ratio read "no lock" instead of crashing on an out-of-bounds pixel.
        int yLo = Math.max(ry0, 0), yHi = Math.min(ry1, img.getHeight());
        int xLo = Math.max(rx0, 0), xHi = Math.min(rx1, img.getWidth());
        List<double[]> clusters = new ArrayList<>(); // {x, y, count}
        for (int y = yLo; y < yHi; y++) {
            for (int x = xLo; x < xHi; x++) {
                if (!isPin(img.getRGB(x, y))) continue;
                boolean placed = false;
                for (double[] c : clusters) {
                    if (Math.hypot(c[0] - x, c[1] - y) <= clusterRadius) {
                        c[0] = (c[0] * c[2] + x) / (c[2] + 1);
                        c[1] = (c[1] * c[2] + y) / (c[2] + 1);
                        c[2]++;
                        placed = true;
                        break;
                    }
                }
                if (!placed) clusters.add(new double[] {x, y, 1});
            }
        }
        List<Pin> pins = new ArrayList<>();
        for (double[] c : clusters) {
            if (c[2] >= pinMinPixels && c[2] <= pinMaxPixels) {
                pins.add(new Pin(c[0], c[1], (int) c[2]));
            }
        }
        return pins;
    }

    /** Warm brass test: reddish-gold, clearly warmer than neutral metal or dark holes. */
    private static boolean isPin(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r - b) >= 45 && (r - g) >= 12 && (g - b) >= 8 && r >= 130 && b <= 140 && g >= 90;
    }

    /** Rec. 601 luma of a packed ARGB pixel. */
    static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }
}
