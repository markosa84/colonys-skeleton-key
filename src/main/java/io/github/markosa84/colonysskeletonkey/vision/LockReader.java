package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
public final class LockReader implements LockAnalyzer {

    /** Offset returned when a plate's hole row could not be read (see {@link #readState}). */
    public static final int UNKNOWN = LockModel.UNKNOWN;

    /** Dark holes per plate: the 7 holes minus the one the pin occupies. */
    private static final int HOLES_PER_PLATE = FanGeometry.HOLES_PER_PLATE;

    // --- Photometry. Everything below is a pixel VALUE, fitted on one screen at gamma 2.7, and it
    // --- is the fragile half of this class: the gamma slider moves every one of these numbers, and
    // --- an HDR tonemap moves them further than any Tone curve can put back. Where the lock IS lives
    // --- in FanGeometry, is measured rather than fitted, and has never had to change.

    // --- Pin detection ---

    /** Merge radius for warm pixels into one pin blob, and the accepted blob pixel-count window. */
    private static final int CLUSTER_RADIUS = 24;
    private static final int PIN_MIN_PIXELS = 45;
    private static final int PIN_MAX_PIXELS = 700;
    /** Max distance (px) a detected pin may sit from a plate's fan position to match it. */
    private static final double MATCH_MAX_DIST = 26.0;

    /**
     * How many holes must sit on the row one step past the end of a fan before {@link #plateBeyond}
     * calls that a <b>plate</b> - and therefore calls the fan the middle of a bigger one.
     *
     * <p>The question "is there another plate out there?" used to be asked of the <i>pins</i>, and it
     * cannot be: a pin is one small warm blob, and rooms are full of those. Measured over the whole
     * corpus, a stray warm blob lands on a genuine 4/5-plate lock's extension position at up to
     * <b>14.8x</b> the pin-size floor (the front-plate-sweep room has something warm sitting exactly
     * there), while a real outer pin - at gamma 1.2 - falls to <b>0.89x</b> it. The two sets overlap
     * completely, so no size threshold exists, and the {@code CLUTTER_ALLOWANCE} that used to paper
     * over this simply <b>switched the check off</b> on any busy frame. That is the hole the
     * wrong-model bug walked through.
     *
     * <p>A plate is not a warm dot: it is a <b>row of six holes on the hole lattice</b>. Ask that
     * instead and the answer is unambiguous - measured across the corpus at every resolution, the row
     * one step past a genuine 4/5-plate lock's end holds <b>0</b> holes, and where a real plate sits
     * it holds <b>6</b>. A candle is not a row of holes. Three is the midpoint of a separation that
     * wide.
     */
    private static final int PLATE_MIN_HOLES = 3;

    // --- Off-centre offset reading (rotate so hole rows are horizontal, then count dark holes).
    // --- The rotation and the deskew are geometry, and live in FanGeometry.

    /** Luminance below which a pixel is a hole candidate. */
    private static final int HOLE_DARK_MAX = 105;
    /** Accepted hole blob area (px). Measured 150-950; the width/height bounds are in FanGeometry. */
    static final int HOLE_MIN_AREA = 150, HOLE_MAX_AREA = 950;
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
     * A double hole spacing, which {@link #walk} accepts to repair a single undetected hole mid-row.
     * The single-step window itself is geometry ({@code FanGeometry.stepMin/Max/Ideal}).
     */
    static final double SKIP_MIN = 80, SKIP_MAX = 118, SKIP_IDEAL = 96;

    // --- The same quantities, mapped onto the actual viewport ---

    /** Where the lock is. Measured, shared with every other reader, and never fitted to a screen. */
    private final FanGeometry geo;
    private final double clusterRadius;
    private final double pinMinPixels, pinMaxPixels;
    private final double matchMaxDist;
    private final double holeMinArea, holeMaxArea;
    private final double skipMin, skipMax, skipIdeal;

    /**
     * The game's gamma, undone on the way in. Every colour and luminance constant above was fitted
     * at one gamma, and the game's slider spans 1.2-3.2; mapping each pixel back to the calibrated
     * look is what lets them all keep their meaning. {@link Tone#CALIBRATED} is the identity, so a
     * reader built without one behaves exactly as it always did - which is what the whole labelled
     * corpus goes on vouching for. See {@link Tone}.
     */
    private final Tone tone;

    /** A reader for frames already at the calibrated gamma - the fixtures, and a 2.7 player. */
    public LockReader(Viewport viewport) {
        this(viewport, Tone.CALIBRATED);
    }

    public LockReader(Viewport viewport, Tone tone) {
        this(viewport.mapping(), tone);
    }

    /** Against a mapping directly, however it was arrived at. See {@link FanGeometry}'s. */
    public LockReader(ViewMapping mapping, Tone tone) {
        this.tone = tone;
        this.geo = new FanGeometry(mapping);
        clusterRadius = mapping.len(CLUSTER_RADIUS);
        pinMinPixels = mapping.area(PIN_MIN_PIXELS);
        pinMaxPixels = mapping.area(PIN_MAX_PIXELS);
        matchMaxDist = mapping.len(MATCH_MAX_DIST);
        holeMinArea = mapping.area(HOLE_MIN_AREA);
        holeMaxArea = mapping.area(HOLE_MAX_AREA);
        skipMin = mapping.len(SKIP_MIN);
        skipMax = mapping.len(SKIP_MAX);
        skipIdeal = mapping.len(SKIP_IDEAL);
    }

    /** A detected pin: screen centroid and blob size (px); the pop threshold lives in the reader. */
    record Pin(double x, double y, int size) {}

    /** Expected pin position of plate {@code i} in an {@code n}-plate fan (offset-independent). */
    double[] pinPosition(int n, int i) {
        return geo.pinPosition(n, i);
    }

    /** The pin-detection scan box, for {@code CaptureBoxTest}'s containment proof. */
    java.awt.Rectangle pinBox() {
        return geo.pinBox();
    }

    /**
     * The screen-space bounding box of every source pixel {@link #readState} samples for an
     * {@code n}-plate lock. {@code CaptureBoxTest} asserts that {@link GameScreen}'s capture box
     * contains this (plus a safety belt) at every viewport.
     */
    java.awt.Rectangle fanCropScreenBounds(int n) {
        return geo.fanCropScreenBounds(n);
    }

    /**
     * Detects the plate count by finding the brass pins. Since pins sit at fixed fan positions, it
     * returns the largest {@code n} (4-7) whose every fan position is covered by a pin - stray warm
     * blobs in the box are simply left unmatched. Returns -1 if no fan fits. Works at any offsets.
     *
     * <p>A fan that fits is not yet an answer, because the fans of {@code n} and {@code n + 2}
     * <b>share a lattice</b> (see {@link #plateBeyond}): the largest fan that fits is only the right
     * one while every pin is seen, and the pin that goes missing is always the faintest. So an
     * {@code n} that could be the middle of a bigger fan must also show that <b>no plate</b> sits one
     * step past either end - which is asked of the hole rows, not of the pins. That costs a rotation,
     * so it is paid only when such an {@code n} actually fits: a 6- or 7-plate lock never reaches it.
     */
    public int detectPlateCount(BufferedImage img) {
        List<Pin> pins = detectPins(img);
        List<double[]> holes = null;
        for (int n = LockModel.MAX_PLATES; n >= LockModel.MIN_PLATES; n--) {
            if (!fanFits(pins, n)) {
                continue;
            }
            if (n + 2 > LockModel.MAX_PLATES) {
                return n; // nothing bigger shares this lattice, so there is no ambiguity to resolve
            }
            if (holes == null) {
                holes = fanHoles(img, LockModel.MAX_PLATES);
            }
            if (!plateBeyond(holes, n)) {
                return n;
            }
        }
        return -1;
    }

    /**
     * Why {@link #detectPlateCount} said what it said, in prose - the first thing to look at when a
     * user reports "no lock detected" over a screenshot that looks perfectly fine.
     *
     * <p>It answers the one question that separates the plausible causes, and it leads with the
     * {@link Tone} it read, because the failures look alike from the outside. <b>No warm blobs at
     * all</b> means the scan box is not over the lock (the viewport describes the wrong rectangle -
     * the game is not filling the display the tool measured), or the colours moved out from under
     * {@code isPin}'s gates and the tone did not put them back. <b>Blobs found but no fan fits</b>
     * is the ambiguous one, and it must not be read as a verdict on the viewport: it is equally what
     * a frame whose <b>pins are too faint to all be seen</b> looks like, and that is what two real
     * reports turned out to be. The pin positions below say which - they either land on a fan's
     * lattice or they do not.
     */
    public String describe(BufferedImage img) {
        StringBuilder out = new StringBuilder();
        out.append("frame:   ").append(img.getWidth()).append('x').append(img.getHeight())
                .append("  (the reader expects the game's view, top-left at 0,0)\n");
        out.append(tone.describe()).append('\n');
        java.awt.Rectangle box = pinBox();
        out.append("pin box: ").append(box.x).append(',').append(box.y)
                .append(" ").append(box.width).append('x').append(box.height)
                .append("  pin blob size accepted: ").append(Math.round(pinMinPixels))
                .append("..").append(Math.round(pinMaxPixels)).append("px\n");

        List<Pin> pins = detectPins(img);
        out.append("pins:    ").append(pins.size()).append(" warm blob(s) of pin size");
        if (pins.isEmpty()) {
            out.append("\n  -> Nothing brass-coloured in the box. Either the box is not over the "
                    + "lock (wrong viewport: is the game really filling this rectangle?), or the "
                    + "frame's colours are shifted (HDR, a shader mod - the gamma slider is handled).");
            return out.toString();
        }
        out.append('\n');
        for (Pin p : pins) {
            out.append(String.format(Locale.ROOT, "  at %.0f,%.0f  %dpx%n", p.x(), p.y(), p.size()));
        }
        List<double[]> holes = fanHoles(img, LockModel.MAX_PLATES);
        for (int n = LockModel.MAX_PLATES; n >= LockModel.MIN_PLATES; n--) {
            out.append(fanReport(pins, holes, n));
        }
        int n = detectPlateCount(img);
        out.append(n < 0
                ? "  -> No fan fits, so no lock was read. If the pins above sit a fan step apart on "
                        + "one of the lattices, they ARE the lock and some of them were too faint to "
                        + "see: the frame's colours, not its coordinates. If they are scattered "
                        + "anywhere else, the scale or the origin is off and the viewport is wrong."
                : "  -> " + n + " plates.");
        return out.toString();
    }

    /**
     * One line per plate count: which of its fan positions no pin covers, and - for a fan that could
     * be the middle of a bigger one - what the rows one step past its ends hold.
     */
    private String fanReport(List<Pin> pins, List<double[]> holes, int n) {
        boolean[] used = new boolean[pins.size()];
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int j = matchPin(pins, used, n, i);
            if (j < 0) {
                double[] want = pinPosition(n, i);
                missing.add(String.format(Locale.ROOT, "%d(%.0f,%.0f)", i, want[0], want[1]));
            } else {
                used[j] = true;
            }
        }
        if (!missing.isEmpty()) {
            return String.format(Locale.ROOT, "  %d plates: no pin within %dpx of %s%n",
                    n, Math.round(matchMaxDist), String.join(" ", missing));
        }
        StringBuilder sizes = new StringBuilder();
        for (int i = 0; i < n; i++) {
            boolean[] fresh = new boolean[pins.size()];
            int j = matchPin(pins, fresh, n, i);
            sizes.append(i > 0 ? "," : "").append(j < 0 ? "-" : pins.get(j).size());
        }
        String beyond = "";
        if (n + 2 <= LockModel.MAX_PLATES) {
            int back = holesOnRow(holes, n, -1);
            int front = holesOnRow(holes, n, n);
            beyond = String.format(Locale.ROOT,
                    "  (rows one step past the ends hold %d and %d holes)", back, front);
            if (plateBeyond(holes, n)) {
                beyond += ", so a PLATE sits past the end and this is the middle of a "
                        + (n + 2) + "-plate fan";
            }
        }
        return String.format(Locale.ROOT, "  %d plates: every fan position covered [%s]px%s%n",
                n, sizes, beyond);
    }

    /**
     * Reads every plate's offset for a lock known to have {@code n} plates: (holes left of the pin) -
     * {@link LockModel#MAX_OFFSET}. A plate whose row does not yield exactly {@link #HOLES_PER_PLATE}
     * holes reads {@link #UNKNOWN}; see {@link LockModel#isComplete}.
     */
    public int[] readState(BufferedImage img, int n) {
        double[][] rowPin = new double[n][];
        for (int i = 0; i < n; i++) {
            rowPin[i] = geo.rowPin(n, i);
        }
        List<double[]> holes = fanHoles(img, n);

        List<List<Double>> rows = new ArrayList<>();
        for (int i = 0; i < n; i++) rows.add(new ArrayList<>());
        for (double[] h : holes) {
            int row = nearestRow(rowPin, h[1]);
            if (onRow(h, rowPin[row], FanGeometry.rowSlope(n, row))) {
                rows.get(row).add(h[0]);
            }
        }

        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
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

    /**
     * Every hole blob in the rotated crop of an {@code n}-plate fan, in rotated-frame coordinates.
     * The crop is sized from the row gates, which already span the whole track, so it depends only on
     * the plate count and never on the offsets - which is what lets {@link #detectPlateCount} take
     * the {@link LockModel#MAX_PLATES} crop once and test any candidate's rows against it.
     */
    private List<double[]> fanHoles(BufferedImage img, int n) {
        int[] crop = geo.fanCrop(n);
        int x0 = crop[0], y0 = crop[1];
        return detectHoles(geo.rotateFan(img, x0, y0, crop[2] - x0, crop[3] - y0), x0, y0);
    }

    /** True if a hole blob sits on the deskewed line of a row whose pin rotates to {@code rowPin}. */
    private boolean onRow(double[] hole, double[] rowPin, double slope) {
        double lineY = rowPin[1] + (hole[0] - rowPin[0]) * slope;
        return Math.abs(hole[1] - lineY) <= geo.rowMaxDy
                && Math.abs(hole[0] - rowPin[0]) <= geo.rowMaxDx;
    }

    /** How many holes sit on row {@code i} of an {@code n}-plate fan. {@code i} may be -1 or {@code n}. */
    private int holesOnRow(List<double[]> holes, int n, int i) {
        double[] rowPin = geo.rowPin(n, i);
        double slope = FanGeometry.rowSlope(n, i);
        int count = 0;
        for (double[] h : holes) {
            if (onRow(h, rowPin, slope)) {
                count++;
            }
        }
        return count;
    }

    /**
     * True if a <b>plate</b> sits one step past either end of an {@code n}-plate fan - which makes
     * this fan the middle of an {@code (n + 2)}-plate one, and {@code n} the wrong answer.
     *
     * <p>Plate {@code i} of {@code n} sits at {@code (mid - i)} depth steps with {@code mid = (n-1)/2},
     * so the fans of {@code n} and {@code n + 2} share a lattice: a 6-plate lock's pins sit at
     * {@code +-2.5, +-1.5, +-0.5} steps and therefore <b>always cover a 4-plate fan</b>
     * ({@code +-1.5, +-0.5}) as well; a 7-plate lock always covers a 5-plate one. Taking the largest
     * fan that fits works only while every pin is seen - and lose the two end pins, which is exactly
     * what a dark or HDR-tonemapped frame does to the faintest ones, and the reader does not fail: it
     * silently answers the <b>smaller lock</b>, hands the session a model with the wrong number of
     * plates, and drives them into walls until the picks run out. A real report did precisely that -
     * a 6-plate chest read as 4 plates, nine strains, "Stuck".
     *
     * <p>So the fan must show that nothing is out there, and the pins cannot show it: see
     * {@link #PLATE_MIN_HOLES} for why (rooms are full of warm dots, and the check that asked about
     * them had to be switched off on any busy frame to stop it rejecting good locks). The hole rows
     * can, because a plate is a row of six holes and nothing else in a room is.
     */
    private boolean plateBeyond(List<double[]> holes, int n) {
        return holesOnRow(holes, n, -1) >= PLATE_MIN_HOLES
                || holesOnRow(holes, n, n) >= PLATE_MIN_HOLES;
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
                int step = (d >= geo.stepMin && d <= geo.stepMax) ? 1
                        : (allowSkip && d >= skipMin && d <= skipMax) ? 2 : 0;
                if (step == 0) continue;
                double err = Math.abs(d - (step == 1 ? geo.stepIdeal : skipIdeal));
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
                int l = luminance(tone.map(crop.getRGB(x, y)));
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
                    || bw < geo.holeMinW || bw > geo.holeMaxW
                    || bh < geo.holeMinH || bh > geo.holeMaxH
                    || minLum > HOLE_MAX_MIN_LUM || sumLum / area > HOLE_MAX_MEAN_LUM) {
                continue;
            }
            out.add(new double[] {x0 + (double) sumX / area, y0 + (double) sumY / area});
        }
        return out;
    }

    /** Index of the unused detected pin closest to plate {@code i}'s fan position, or -1. */
    private int matchPin(List<Pin> pins, boolean[] used, int n, int i) {
        double[] c = geo.pinPosition(n, i);
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

    /**
     * True if every fan position of an {@code n}-plate lock is covered by a detected pin. Stray warm
     * blobs are simply left unmatched - and whether the fan is the <b>whole</b> lock or merely the
     * middle of a bigger one is not a question the pins can answer: {@link #plateBeyond} does that.
     */
    private boolean fanFits(List<Pin> pins, int n) {
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
        int yLo = Math.max(geo.ry0, 0), yHi = Math.min(geo.ry1, img.getHeight());
        int xLo = Math.max(geo.rx0, 0), xHi = Math.min(geo.rx1, img.getWidth());
        List<double[]> clusters = new ArrayList<>(); // {x, y, count}
        for (int y = yLo; y < yHi; y++) {
            for (int x = xLo; x < xHi; x++) {
                if (!isPin(tone.map(img.getRGB(x, y)))) continue;
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

    /**
     * Warm brass test: reddish-gold, clearly warmer than neutral metal or dark holes. Absolute
     * numbers, so the pixel must arrive at the <b>calibrated gamma</b> - {@link #detectPins} maps it
     * through the {@link Tone} first. Both of these gates fail on a raw frame from the wrong end of
     * the game's gamma slider ({@code r >= 130} in the dark, {@code b <= 140} in the bright), and
     * both take the pin blob's <i>size</i> down with them, which is what the pop signal is made of.
     */
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
