package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Finds the lock by its own hole lattice, and reports where it must be - rather than being told.
 *
 * <p>Everything else in this package is handed a {@link ViewMapping} derived from the game window's
 * measured rectangle, and every one of those measurements is a guess that <b>cannot fail loudly</b>.
 * Get the rectangle wrong and the reader scans a box the lock is not in, finds nothing, and the
 * player is told "no lock detected" over a screenshot in which the lock is plainly visible. That is
 * the single biggest real failure class this tool has had: it shipped for a year measuring the
 * display and assuming the game filled it (see {@link Viewport}, and {@code WindowedGameTest}).
 *
 * <p>So this class asks the pixels instead. It is the second opinion, and it is only ever consulted
 * when the first one has already failed.
 *
 * <h2>What it looks for, and why that is a strong thing to look for</h2>
 * A lock is <b>24 to 42 dark wells arranged on one two-basis lattice at a fixed angle</b>. That
 * conjunction is what makes this workable where every previous attempt at finding the lock by
 * appearance failed. A pin is a warm dot and a room is full of warm dots - measured, a stray blob
 * lands on a real lock's fan position at up to 14.8x the pin-size floor, which is why the old
 * clutter allowance had to switch itself off on busy frames and why that is a documented dead end. A
 * lattice is different in kind: four or more parallel rows, six holes each, spaced alike, at a known
 * angle, with a second basis of a known ratio and angle to the first. Nothing in a room is that by
 * accident.
 *
 * <h2>Order statistics only - no level means anything here</h2>
 * The wells are found by a threshold sweep and kept for being <b>stable</b> across it (MSER's idea).
 * Every threshold is a percentile of this frame's own non-black pixels, and every gate downstream is
 * a ratio or an angle. A monotone tone map - which is what both the gamma LUT and an HDR tonemap
 * are - permutes no pixel's rank, so it moves nothing here. There is no {@link Tone} in this class
 * and there must not be: the frames this exists to rescue are exactly the ones a tone curve gets
 * wrong.
 *
 * <h2>It proposes; the reader disposes</h2>
 * The lattice fit is a proposal. A pose is only returned once {@link LatticeReader}, built on it,
 * confirms a 4-7 plate lock at it - which is a far stronger test than any of the geometric gates,
 * and it is the same test the tool would have applied anyway. So a false accept has to fool the
 * lattice fit <b>and</b> the reader, and the reader's own whole-corpus record is that it never
 * reports a lock that is not there.
 *
 * <p>The lattice is fitted only to <b>locate</b>. It does not read: a rigid lattice fit for reading
 * was tried and it fails, because perspective runs the hole spacing 41-54px along a single row and
 * no rigid lattice holds all seven slots at once. Reading stays the hole-to-hole walk, which lets
 * the row bend as the camera bends it. See CLAUDE.md's dead ends.
 */
public final class FanLocator {

    /** Long side the frame is worked at. Fine enough for a hole, ~9x cheaper than 4K. */
    private static final int WORK_LONG_SIDE = 1280;

    /**
     * Luminance at or below which a pixel is not part of the picture at all. The fixtures are shrunk
     * (everything outside the lock box painted black) and a live poll is composited onto a canvas
     * that is black everywhere else, so both hand this class a huge, perfectly black field. Left in,
     * it owns every low percentile and crushes the ladder into it.
     */
    private static final int FIELD_MAX_LUM = 2;

    /**
     * The threshold ladder: this many levels, evenly spaced in <b>rank</b> across the frame's own
     * non-black pixels.
     *
     * <p>Rank, not luminance, is what makes the sweep tone-free: a monotone map - a gamma LUT, an
     * HDR tonemap - relabels every level but reorders no pixel, so a ladder built on ranks lands in
     * exactly the same places on the mapped frame. Classic MSER measures its stability in levels
     * instead, and is therefore only invariant to an affine change, which is not what the game does.
     *
     * <p>The span has to be nearly the whole range, and that is measured, not cautious. A hole's
     * interior is at the room's darkness and the steel it is cut into is at the far bright end - on
     * a 4K fixture the holes sit at luminance 1-10 and the steel at 239, which is rank ~40% against
     * rank ~94%. A hole is only <i>stable</i> for thresholds in between, so a ladder over the low
     * percentiles alone (which is where the first version of this stopped, at 30%) never reaches the
     * range where a hole holds still, and finds almost nothing. The dark end still has to be
     * excluded, because the shrunk fixtures and the live canvas are mostly black field.
     */
    private static final int LADDER_LEVELS = 24;
    private static final double LADDER_LO_PCT = 0.02, LADDER_HI_PCT = 0.98;

    /**
     * A component bigger than this share of the worked frame is the room or the black field, not a
     * hole. There is deliberately no matching floor beyond a few pixels: the scale is the thing
     * being solved for, so a size gate in absolute pixels would assume the answer.
     */
    private static final double WELL_MAX_AREA_SHARE = 0.001;
    private static final int WELL_MIN_AREA = 4;

    /** A hole's bounding box: measured 15-42 wide by 14-34 tall, so 0.44 to 3.0 either way up. */
    private static final double WELL_MIN_ASPECT = 0.4, WELL_MAX_ASPECT = 3.0;
    /** How much of its bounding box a hole fills. A hole is a blob; a streak or a crack is not. */
    private static final double WELL_MIN_FILL = 0.4;

    /** Stability: a well must survive this many consecutive levels, growing no more than this. */
    private static final int STABLE_LEVELS = 3;
    private static final double STABLE_MAX_GROWTH = 2.5;
    /** How near two levels' blobs must be to be the same well, in worked pixels. */
    private static final double SAME_WELL = 3.0;

    /**
     * How far a <b>single</b> hole-to-hole displacement's angle may sit from the row direction and
     * still be taken as a step along a row.
     *
     * <p>Wider than the row angle's own measured band (-32.5..-28.3, so 2.2 either side of
     * {@code ROT_DEG}), and it has to be, for two reasons that both bite hardest exactly where the
     * locator can least afford it. Each row carries its own residual tilt against the one global
     * angle - up to 2.3 degrees, and worst on the <b>front and back rows</b>, which are the ones a
     * fan can least afford to lose. And a well's centroid is only good to about a pixel, which on a
     * 43px baseline is another 1.3 degrees. At 2.5 the two together dropped four fifths of the real
     * holes, the back row of a 5-plate lock entirely.
     *
     * <p>It stays selective because the thing it must not admit is 62 degrees away: the fan's other
     * basis, the step from one plate to the next, runs at -31.96 on screen against the row's +30.
     */
    private static final double ROW_ANGLE_TOL_DEG = 8.0;

    /** How many nearest neighbours each well votes with, and the length histogram's bin ratio. */
    private static final int NEIGHBOURS = 4;
    private static final double LENGTH_BIN = 0.06;

    /**
     * How far off the voted spacing a well's neighbour may sit and still count as the next hole
     * along its row. Wide, because it has to be: perspective runs the real spacing 41-54 against an
     * ideal of 48, so a single number for "one hole along" is already a compromise.
     */
    private static final double SPACING_TOL = 0.35;

    /** Rows are split where the gap across them exceeds this share of the hole spacing. */
    private static final double ROW_GAP = 0.6;
    /** Consecutive holes along a row, and the double gap the pin's own slot leaves. */
    private static final double CHAIN_MIN = 0.75, CHAIN_MAX = 1.35;
    private static final double PIN_GAP_MIN = 1.6, PIN_GAP_MAX = 2.5;

    /** A row of fewer than this many wells is not evidence of a plate. */
    private static final int ROW_MIN_WELLS = 4;

    /** How far two pin hypotheses may sit apart and count as the same one, in hole spacings. */
    private static final double SAME_PIN = 0.25;

    /**
     * How many times the scale and the pins are measured against each other. Two would do - the
     * first pass is already within a few percent - and the third is there to show it has stopped
     * moving rather than to move it.
     */
    private static final int REFINE_PASSES = 3;

    // --- acceptance ------------------------------------------------------------------------------

    private static final int MIN_ROWS = LockModel.MIN_PLATES;
    private static final int MIN_WELLS = 18;
    /** The two scale readings - across the rows, and along them - must agree this closely. */
    private static final double SCALE_AGREE = 0.15;
    /** How much of the fan the implied pose puts on the frame. A lock half off it is not the lock. */
    private static final double CROP_IN_FRAME = 0.85;
    /** Poses offered to the reader, most likely first. Each costs a read, and this runs once per F8. */
    private static final int MAX_POSES = 12;
    /** How many pin lines are handed to the reader to choose between. See {@link #pinLines}. */
    private static final int MAX_PIN_LINES = 4;

    /**
     * What the locator made of one frame: a pose, or the reason there is none - and an account
     * either way, because a refusal is the interesting case and the report has to say how far it
     * got.
     *
     * @param mapping where the lock is, or <b>null</b> when it was not found
     * @param rows    how many hole rows the lattice fit ended up with
     * @param wells   how many dark wells it was fitted to
     * @param rms     how far, rms, those wells sit from their own rows' lines
     */
    public record Fit(ViewMapping mapping, int rows, int wells, double rms, String account) {

        static Fit refused(String account) {
            return new Fit(null, 0, 0, Double.NaN, account);
        }

        /** True when {@link #mapping()} is an answer rather than a null. */
        public boolean found() {
            return mapping != null;
        }
    }

    /**
     * Where the lock in this frame is, or why that could not be said.
     *
     * <p>The frame is the game's <b>view</b>, as everything else in this package takes it, and the
     * pose returned is view-local like every other mapping here.
     */
    public Fit locate(BufferedImage frame) {
        Plane plane = Plane.of(frame);
        List<double[]> wells = wells(plane);
        if (wells.size() < MIN_WELLS) {
            return Fit.refused(account(plane, wells.size(), 0,
                    "only " + wells.size() + " stable dark wells - a lock is at least " + MIN_WELLS
                            + ". Either there is no lock in this frame, or its holes are not "
                            + "resolving at this size."));
        }
        // A rough hole spacing, to bootstrap with. It is only ever a bootstrap: the wells still
        // include every dark mark in the room, and those drag the vote low - measured, 17% low on a
        // 4K fixture. What it is good enough for is throwing most of them away.
        double rough = holeSpacing(wells);
        if (rough <= 0) {
            return Fit.refused(account(plane, wells.size(), 0,
                    "the " + wells.size() + " wells do not line up: none of the ways they sit "
                            + "relative to each other repeats at the row's own angle, so they are "
                            + "scattered marks rather than hole rows."));
        }
        // Now throw away everything that is not on that lattice. A room is full of dark marks and
        // they arrive as wells like any other; what none of them has is a partner one hole spacing
        // away along the row's own angle. Skipping this leaves the marks in the crowd, where they
        // bridge two rows into one, invent rows of their own, and - worst, because it is silent -
        // drag the fan's centre off the average of the real rows.
        List<double[]> onLattice = onLattice(wells, rough);
        List<Lattice> lattices = fit(onLattice, rough);
        if (lattices.isEmpty()) {
            return Fit.refused(account(plane, wells.size(), rows(onLattice, rough).size(),
                    "the wells that sit on a row do not make a fan: either too few rows, or their "
                            + "spacing across the rows and along them cannot be one scale, or no "
                            + "slot-with-no-hole in one row predicts the others' - so these are not "
                            + "one fan's plates."));
        }
        int rows = rows(onLattice, FanGeometry.holeStep() * lattices.getFirst().scale).size();
        int tried = 0;
        for (Lattice lattice : lattices) {
            for (ViewMapping pose : lattice.poses(frame)) {
                if (++tried > MAX_POSES) {
                    break;
                }
                if (confirms(frame, pose)) {
                    return new Fit(pose, rows, wells.size(), lattice.rms,
                            found(plane, pose, lattice, wells.size(), rows));
                }
            }
        }
        return Fit.refused(account(plane, wells.size(), rows,
                String.format(Locale.ROOT,
                        "a lattice of %d rows fits at scale %.3f, but the reader reads no lock at "
                        + "any of the %d poses it implies. Being a lattice is not being a lock.",
                        rows, lattices.getFirst().scale, tried)));
    }

    /**
     * The final authority on a proposed pose: the reader must <b>read</b> a lock at it - not merely
     * count one.
     *
     * <p>Counting is not enough, and that was measured rather than feared. Asking only
     * {@code detectPlateCount ∈ 4..7} accepted a pose <b>34px out</b> - 0.71 of a hole spacing - on
     * {@code 7p-plate-2-sweep}. It can, because a row counts as a plate on
     * {@code (walked == 6 || popped) && lit >= LIT_PLATE}: a popped pin sitting in lit steel carries
     * a row whose holes never resolved at all. A locator that only has to satisfy that can be quite
     * wrong and still be believed, which defeats the point of having an authority.
     *
     * <p>So every row must resolve as well: no {@link LockModel#UNKNOWN} in the state. That is the
     * hole-to-hole walk landing on six holes in every row, which a pose off by a fraction of a
     * spacing does not survive - the walk's steps stop adding up. It is a strict bar, and it should
     * be: this is a pose the tool <i>invented</i>, on a frame it had already failed to read, and a
     * wrong one is worse than none. A lock the reader can find but not read is left to the reader's
     * own machinery, which is built for it; the locator's job is only to say where to look.
     *
     * <p>Always the {@link LatticeReader}, whatever {@code --reader} the run is using. This is a
     * question about <b>where the lock is</b>, and the tone-free reader is strictly the better
     * instrument for it - a frame whose colours the calibrated reader cannot handle is exactly the
     * kind of frame that got here.
     */
    private static boolean confirms(BufferedImage frame, ViewMapping pose) {
        LatticeReader reader = new LatticeReader(pose, Tone.estimate(frame, pose));
        int n = reader.detectPlateCount(frame);
        if (n < LockModel.MIN_PLATES || n > LockModel.MAX_PLATES) {
            return false;
        }
        for (int offset : reader.readState(frame, n)) {
            if (offset == LockModel.UNKNOWN) {
                return false;
            }
        }
        return true;
    }

    // --- 1. the wells ----------------------------------------------------------------------------

    /**
     * The frame as a luminance plane, box-averaged down so its long side is about
     * {@link #WORK_LONG_SIDE}. Averaging is not only cheaper - it is what turns a 4K hole into a
     * blob a threshold sweep can hold on to, instead of a field of individually noisy pixels.
     *
     * @param by the integer factor the frame was shrunk by, so a well can be put back
     */
    record Plane(int[] lum, int w, int h, int by) {

        static Plane of(BufferedImage img) {
            int by = Math.max(1, (int) Math.round(
                    Math.max(img.getWidth(), img.getHeight()) / (double) WORK_LONG_SIDE));
            int w = Math.max(1, img.getWidth() / by), h = Math.max(1, img.getHeight() / by);
            int[] lum = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int sum = 0;
                    for (int dy = 0; dy < by; dy++) {
                        for (int dx = 0; dx < by; dx++) {
                            sum += LockReader.luminance(img.getRGB(x * by + dx, y * by + dy));
                        }
                    }
                    lum[y * w + x] = sum / (by * by);
                }
            }
            return new Plane(lum, w, h, by);
        }

        /** A worked-plane point, back in the frame's own coordinates. */
        double[] toFrame(double x, double y) {
            return new double[] {(x + 0.5) * by - 0.5, (y + 0.5) * by - 0.5};
        }
    }

    /**
     * Every stable dark well in the plane, as frame-space centroids.
     *
     * <p>This is MSER's idea - sweep a threshold, keep what does not change - done as a sweep of
     * flood fills rather than the incremental component tree. The tree is the faster algorithm and
     * this is the clearer one, and clarity is what this needs: the locator runs once per F8, and
     * only on a frame the tool has already failed to read.
     *
     * <p>A hole is dark because it is a hole - what shows through it is the room behind the lock -
     * so it appears at a low threshold and then stops growing, because the lit steel around it is
     * far brighter. A shadow on the steel has no such floor: it grows into its surroundings as the
     * threshold rises. That is the whole distinction, and it needs no absolute level, only rank.
     */
    List<double[]> wells(Plane p) {
        int[] ladder = ladder(p);
        int cap = Math.max(WELL_MIN_AREA + 1, (int) (WELL_MAX_AREA_SHARE * p.w * p.h));
        boolean[] seen = new boolean[p.w * p.h];
        int[] stack = new int[p.w * p.h];
        List<List<double[]>> perLevel = new ArrayList<>();
        for (int level : ladder) {
            Arrays.fill(seen, false);
            perLevel.add(blobs(p, level, cap, seen, stack));
        }
        // Back into the frame's own pixels: everything downstream is a lattice in the coordinates
        // the mapping will be expressed in, and the plane is an implementation detail of finding it.
        List<double[]> out = new ArrayList<>();
        for (double[] well : stable(perLevel)) {
            out.add(p.toFrame(well[0], well[1]));
        }
        return out;
    }

    /**
     * The threshold ladder: {@link #LADDER_LEVELS} levels spanning the low percentiles of the
     * frame's own <b>non-black</b> pixels. Percentiles, because a level means nothing across
     * displays and a rank means the same on all of them.
     */
    private static int[] ladder(Plane p) {
        int[] hist = new int[256];
        long total = 0;
        for (int l : p.lum) {
            if (l > FIELD_MAX_LUM) {
                hist[l]++;
                total++;
            }
        }
        if (total == 0) {
            return new int[0];
        }
        Set<Integer> levels = new LinkedHashSet<>();
        for (int k = 0; k < LADDER_LEVELS; k++) {
            double pct = LADDER_LO_PCT
                    + (LADDER_HI_PCT - LADDER_LO_PCT) * k / (LADDER_LEVELS - 1.0);
            levels.add(percentile(hist, total, pct));
        }
        int[] out = new int[levels.size()];
        int i = 0;
        for (int level : levels) {
            out[i++] = level;
        }
        Arrays.sort(out);
        return out;
    }

    private static int percentile(int[] hist, long total, double pct) {
        long want = (long) Math.ceil(pct * total);
        long seen = 0;
        for (int v = 0; v < hist.length; v++) {
            seen += hist[v];
            if (seen >= want) {
                return v;
            }
        }
        return hist.length - 1;
    }

    /** Hole-shaped connected components of {@code lum <= level}, as {@code {x, y, area}}. */
    private static List<double[]> blobs(Plane p, int level, int cap, boolean[] seen, int[] stack) {
        int n = p.w * p.h;
        List<double[]> out = new ArrayList<>();
        for (int seed = 0; seed < n; seed++) {
            if (seen[seed] || p.lum[seed] > level) {
                continue;
            }
            int sp = 0;
            stack[sp++] = seed;
            seen[seed] = true;
            int area = 0, minX = p.w, maxX = -1, minY = p.h, maxY = -1;
            long sumX = 0, sumY = 0;
            boolean tooBig = false;
            while (sp > 0) {
                int q = stack[--sp];
                int x = q % p.w, y = q / p.w;
                area++;
                sumX += x;
                sumY += y;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                tooBig |= area > cap;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= p.w || ny >= p.h) {
                            continue;
                        }
                        int r = ny * p.w + nx;
                        if (!seen[r] && p.lum[r] <= level) {
                            seen[r] = true;
                            stack[sp++] = r;
                        }
                    }
                }
            }
            if (tooBig || area < WELL_MIN_AREA) {
                continue; // the room, the black field, or a speck
            }
            int bw = maxX - minX + 1, bh = maxY - minY + 1;
            double aspect = bw / (double) bh;
            if (aspect < WELL_MIN_ASPECT || aspect > WELL_MAX_ASPECT
                    || area < WELL_MIN_FILL * bw * bh) {
                continue;
            }
            out.add(new double[] {(double) sumX / area, (double) sumY / area, area});
        }
        return out;
    }

    /** One dark thing, followed up the ladder: where it was and how big, at each level it survived. */
    private static final class Track {
        private final java.util.Map<Integer, double[]> byLevel = new java.util.HashMap<>();
        private double sumX, sumY;
        private int count;

        void saw(int level, double[] blob) {
            byLevel.put(level, blob);
            sumX += blob[0];
            sumY += blob[1];
            count++;
        }

        boolean isHere(double x, double y) {
            return Math.hypot(sumX / count - x, sumY / count - y) <= SAME_WELL;
        }

        /**
         * Where this is, if it is stable: the centroid over the first window of
         * {@link #STABLE_LEVELS} consecutive levels it barely grew across, or null.
         *
         * <p>Stability is <b>local</b>, and that distinction is the whole of MSER. Every dark thing
         * grows as the threshold climbs from the 2nd percentile to the 30th, so nothing at all
         * survives being asked to grow little across the <i>whole</i> ladder. What separates a hole
         * from a shadow is that a hole has somewhere to stop - the lit steel it is cut into - so
         * there is a stretch of the ladder over which it barely moves. A shadow has no such edge and
         * is still swelling into its surroundings at every level.
         */
        double[] stable(int levels) {
            for (int level = 0; level + STABLE_LEVELS - 1 < levels; level++) {
                double[] first = byLevel.get(level);
                double[] last = byLevel.get(level + STABLE_LEVELS - 1);
                if (first == null || last == null || last[2] > STABLE_MAX_GROWTH * first[2]) {
                    continue;
                }
                double x = 0, y = 0;
                int n = 0;
                for (int k = level; k < level + STABLE_LEVELS; k++) {
                    double[] blob = byLevel.get(k);
                    if (blob == null) {
                        n = 0;
                        break;
                    }
                    x += blob[0];
                    y += blob[1];
                    n++;
                }
                if (n == STABLE_LEVELS) {
                    return new double[] {x / n, y / n};
                }
            }
            return null;
        }
    }

    /**
     * The blobs that are the <b>same</b> blob across a stretch of the ladder and barely grew over
     * it: the stable ones, which is to say the holes.
     */
    private List<double[]> stable(List<List<double[]>> perLevel) {
        List<Track> tracks = new ArrayList<>();
        for (int level = 0; level < perLevel.size(); level++) {
            for (double[] blob : perLevel.get(level)) {
                Track track = null;
                for (Track candidate : tracks) {
                    if (candidate.isHere(blob[0], blob[1])) {
                        track = candidate;
                        break;
                    }
                }
                if (track == null) {
                    track = new Track();
                    tracks.add(track);
                }
                track.saw(level, blob);
            }
        }
        List<double[]> out = new ArrayList<>();
        for (Track track : tracks) {
            double[] at = track.stable(perLevel.size());
            if (at != null) {
                out.add(at);
            }
        }
        return out;
    }

    // --- 2. the row basis ------------------------------------------------------------------------

    /**
     * How far apart the wells sit <b>along a row</b>, in frame pixels - the first basis vector's
     * length, and the only part of it that is not already known.
     *
     * <p>Its direction is not solved for, it is gated on: a row runs at exactly minus
     * {@code ROT_DEG} on screen, on every frame ever captured, because uniform scaling does not
     * touch an angle and the game's pose does not move. So only displacements already at that angle
     * get a vote, and what is voted on is length alone. Lengths go in ratio bins, not pixel bins:
     * the spacing runs 41-54 reference px with perspective and this has no idea yet what a
     * reference pixel is worth here.
     */
    double holeSpacing(List<double[]> wells) {
        double[] u = FanGeometry.rowDirection();
        double want = Math.toDegrees(Math.atan2(u[1], u[0]));
        List<Double> lengths = new ArrayList<>();
        for (double[] a : wells) {
            for (double[] b : near(wells, a)) {
                double dx = b[0] - a[0], dy = b[1] - a[1];
                double angle = Math.toDegrees(Math.atan2(dy, dx));
                if (fold(angle - want) <= ROW_ANGLE_TOL_DEG) {
                    lengths.add(Math.hypot(dx, dy));
                }
            }
        }
        return peak(lengths);
    }

    /**
     * The wells that have a neighbour <b>one hole spacing away along a row</b> - the ones sitting on
     * the lattice, rather than merely being dark.
     *
     * <p>This is the filter that makes the rest of the pipeline work, and it is the same idea as the
     * reader's own {@code plateBeyond}: a lone dark mark is not evidence of anything, because a room
     * is full of them, and no threshold on a mark's own appearance separates the two populations.
     * What a hole has and a mark does not is a <b>partner</b>, at a known angle and a known
     * distance. Measured on the fixtures, this drops roughly two thirds of the wells and almost
     * none of the holes.
     */
    List<double[]> onLattice(List<double[]> wells, double spacing) {
        double[] u = FanGeometry.rowDirection();
        double want = Math.toDegrees(Math.atan2(u[1], u[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] a : wells) {
            for (double[] b : wells) {
                if (a == b) {
                    continue;
                }
                double dx = b[0] - a[0], dy = b[1] - a[1];
                double len = Math.hypot(dx, dy);
                if (Math.abs(len - spacing) <= SPACING_TOL * spacing
                        && fold(Math.toDegrees(Math.atan2(dy, dx)) - want) <= ROW_ANGLE_TOL_DEG) {
                    out.add(a);
                    break;
                }
            }
        }
        return out;
    }

    /** {@link #NEIGHBOURS} nearest wells to {@code a}, which is where a lattice shows itself. */
    private static List<double[]> near(List<double[]> wells, double[] a) {
        List<double[]> others = new ArrayList<>(wells);
        others.remove(a);
        others.sort(Comparator.comparingDouble(b -> Math.hypot(b[0] - a[0], b[1] - a[1])));
        return others.subList(0, Math.min(NEIGHBOURS, others.size()));
    }

    /** How far an angle is from zero, in degrees, with direction folded away ([0, 90]). */
    private static double fold(double degrees) {
        double d = Math.abs(degrees) % 180;
        return Math.min(d, 180 - d);
    }

    /**
     * The most-repeated length, in {@link #LENGTH_BIN} ratio bins - and then the median of the bin's
     * own votes rather than the bin's centre, because a bin is a way of finding the crowd and not a
     * measurement.
     */
    private static double peak(List<Double> lengths) {
        if (lengths.isEmpty()) {
            return -1;
        }
        double bin = Math.log1p(LENGTH_BIN);
        int best = 0, at = Integer.MIN_VALUE;
        java.util.Map<Integer, List<Double>> bins = new java.util.HashMap<>();
        for (double len : lengths) {
            if (len <= 0) {
                continue;
            }
            int b = (int) Math.floor(Math.log(len) / bin);
            List<Double> in = bins.computeIfAbsent(b, k -> new ArrayList<>());
            in.add(len);
            if (in.size() > best) {
                best = in.size();
                at = b;
            }
        }
        // Two votes are a coincidence; a row's worth of them is a lattice.
        if (best < ROW_MIN_WELLS) {
            return -1;
        }
        List<Double> in = new ArrayList<>(bins.get(at));
        in.sort(Comparator.naturalOrder());
        return in.get(in.size() / 2);
    }

    // --- 3. the rows -----------------------------------------------------------------------------

    /**
     * One hole row, as its own straight line: where it sits across the rows at {@link #atAlong}, the
     * {@link #slope} it climbs at from there, its wells' positions along it, and how far each of
     * those sits off the line.
     *
     * <p>The slope is not a nicety. Each row carries a real residual tilt against the one global
     * rotation angle - up to 2.3 degrees, measured - so "where the row sits across" is only a
     * question with an answer once you say <b>where along it</b> you are asking about. See
     * {@link #acrossAt}.
     */
    record Row(double across, double slope, double atAlong, List<Double> along, List<Double> spread) {

        /**
         * Where this row's line sits across the rows, at a given point along it.
         *
         * <p>This is what fixes the pitch, and the pitch is the only real measurement in the whole
         * locator. Taking a row's across as the mean of its own holes is biased, because the holes
         * are not centred on the pin and the row is tilted: measured on
         * {@code 3840x2160/front-plate-sweep/step-0}, plate 0's holes average 180px to the left of
         * its pin, and at that row's -1.6 degree tilt (the deskew's own prediction) that is 5.0px of
         * across - which accounts for a measured 3.8px error to within a pixel. Every row is wrong
         * by its own amount, in its own direction, so the pitch between them came out 2.8% short;
         * and a 2.8% error in the scale compounds over six slots into more than a hole's width, at
         * which point no pin proposal matches any other and the fan is refused.
         */
        double acrossAt(double along) {
            return across + slope * (along - atAlong);
        }
    }

    /**
     * The wells, grouped into rows: project them across the row direction and cut where the gap is
     * wider than a row is thick. Rows sit {@code rowPitch} apart (61.3 reference px against a hole
     * spacing of 48), so a cut at {@link #ROW_GAP} of the spacing separates them with room to
     * spare, while a row's own perspective tilt drifts its holes only a few px across.
     */
    List<Row> rows(List<double[]> wells, double spacing) {
        double[] u = FanGeometry.rowDirection();
        double nx = -u[1], ny = u[0];
        List<double[]> byAcross = new ArrayList<>(); // {across, along}
        for (double[] well : wells) {
            byAcross.add(new double[] {well[0] * nx + well[1] * ny, well[0] * u[0] + well[1] * u[1]});
        }
        byAcross.sort(Comparator.comparingDouble(a -> a[0]));

        List<Row> rows = new ArrayList<>();
        List<double[]> current = new ArrayList<>();
        for (double[] well : byAcross) {
            if (!current.isEmpty() && well[0] - current.getLast()[0] > ROW_GAP * spacing) {
                addRow(rows, current, spacing);
                current = new ArrayList<>();
            }
            current.add(well);
        }
        addRow(rows, current, spacing);
        return rows;
    }

    /** Keeps a candidate row if what is in it is a chain of holes at the right spacing. */
    private void addRow(List<Row> rows, List<double[]> group, double spacing) {
        if (group.size() < ROW_MIN_WELLS) {
            return;
        }
        List<double[]> sorted = new ArrayList<>(group);
        sorted.sort(Comparator.comparingDouble(a -> a[1]));
        List<double[]> chain = chain(sorted, spacing);
        if (chain.size() < ROW_MIN_WELLS) {
            return;
        }
        // The row's own line, least-squares through its chain: across = a + b * along. The slope is
        // this row's residual tilt against the one global rotation angle - real, measured, and up to
        // 2.3 degrees - so a line has to absorb it before the spread means anything.
        int n = chain.size();
        double sumX = 0, sumY = 0;
        for (double[] well : chain) {
            sumX += well[1];
            sumY += well[0];
        }
        double meanX = sumX / n, meanY = sumY / n;
        double sxy = 0, sxx = 0;
        for (double[] well : chain) {
            sxy += (well[1] - meanX) * (well[0] - meanY);
            sxx += (well[1] - meanX) * (well[1] - meanX);
        }
        double slope = sxx == 0 ? 0 : sxy / sxx;
        List<Double> along = new ArrayList<>();
        List<Double> spread = new ArrayList<>();
        for (double[] well : chain) {
            along.add(well[1]);
            spread.add(well[0] - (meanY + slope * (well[1] - meanX)));
        }
        rows.add(new Row(meanY, slope, meanX, along, spread));
    }

    /**
     * The longest run of wells stepping one hole spacing at a time, allowing <b>one</b> double step:
     * the pin's own slot, which holds no hole. Anything else in the row - a shadow, a rivet - is not
     * on the lattice and simply does not join the chain.
     */
    private static List<double[]> chain(List<double[]> along, double spacing) {
        List<double[]> best = List.of();
        for (int start = 0; start < along.size(); start++) {
            List<double[]> run = new ArrayList<>();
            run.add(along.get(start));
            boolean usedPinGap = false;
            for (int i = start + 1; i < along.size(); i++) {
                double gap = along.get(i)[1] - run.getLast()[1];
                if (gap >= CHAIN_MIN * spacing && gap <= CHAIN_MAX * spacing) {
                    run.add(along.get(i));
                } else if (!usedPinGap && gap >= PIN_GAP_MIN * spacing
                        && gap <= PIN_GAP_MAX * spacing) {
                    run.add(along.get(i));
                    usedPinGap = true;
                }
            }
            if (run.size() > best.size()) {
                best = run;
            }
        }
        return best;
    }

    // --- 4. the pose -----------------------------------------------------------------------------

    /** A fitted lattice: the scale it implies, and where its fan centre sits. */
    private record Lattice(double scale, double centreAlong, double centreAcross, double rms) {

        /**
         * The poses this lattice implies, likeliest first.
         *
         * <p>The fan centre is the centroid of the pins, exactly, for every plate count - so the
         * rows found average to it, <b>provided they are all of them</b>. A row that did not resolve
         * shifts that average by half a plate step per missing row, and the shift is along the depth
         * direction. There is no way to tell from the lattice alone that a row is missing, so the
         * shifts are enumerated instead and the reader is asked about each. Two rows lost is already
         * a lock this class has no business claiming to have found.
         */
        List<ViewMapping> poses(BufferedImage frame) {
            double[] u = FanGeometry.rowDirection();
            List<ViewMapping> out = new ArrayList<>();
            for (double shift : new double[] {0, 0.5, -0.5, 1, -1}) {
                double along = centreAlong + shift * FanGeometry.depthStepAlongRow() * scale;
                double across = centreAcross - shift * FanGeometry.rowPitch() * scale;
                double x = along * u[0] + across * -u[1];
                double y = along * u[1] + across * u[0];
                double[] ref = FanGeometry.referenceFanCenter();
                ViewMapping pose = new ViewMapping(scale, x - ref[0] * scale, y - ref[1] * scale);
                if (onFrame(frame, pose)) {
                    out.add(pose);
                }
                if (out.size() >= MAX_POSES) {
                    break;
                }
            }
            return out;
        }
    }

    /** True when the pose puts enough of the fan on the frame to be a lock the player can see. */
    private static boolean onFrame(BufferedImage frame, ViewMapping pose) {
        FanGeometry geo = new FanGeometry(pose);
        java.awt.Rectangle fan = geo.fanCropScreenBounds(LockModel.MAX_PLATES);
        java.awt.Rectangle on = fan.intersection(
                new java.awt.Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
        if (on.isEmpty()) {
            return false;
        }
        return (double) on.width * on.height >= CROP_IN_FRAME * fan.width * fan.height;
    }

    /**
     * Solves the three unknowns - a scale and a translation; the game's pose is fixed, so there is
     * no rotation to solve for - out of the wells, refining until the answer stops moving.
     *
     * <p>It refines because the two measurements need each other. The <b>scale</b> comes from across
     * the rows, where they sit one {@code rowPitch} apart - the one measurement the room's marks
     * cannot corrupt, because they do not land on the comb the fan's rows make. But a row's across
     * is only well defined <i>at a point along it</i> ({@link Row#acrossAt}), and the point that
     * matters is its <b>pin</b>. And the pins cannot be found without the hole spacing, which is
     * derived from the scale. So: measure the rows roughly, find the pins, measure the rows again at
     * those pins, and repeat. It converges in two passes because the first one is already within a
     * few percent; the loop just stops guessing about it.
     *
     * <p>The pins themselves are a vote. Each row offers every slot it cannot rule out (see
     * {@link #pins}), no row is trusted about itself, and the fan decides: every pin lies on one
     * line along the depth step, so a proposal in any row predicts the pin of every other, and the
     * proposal the rest agree with is the pin.
     */
    private List<Lattice> fit(List<double[]> onLattice, double rough) {
        // Pass one has no scale yet, so it measures the rows where their own holes put them and
        // takes the pitch from that. That is biased by each row's tilt (see Row.acrossAt) - but only
        // by a few percent, which is close enough to find the pins with, and the pins are what let
        // pass two measure the rows where it actually matters.
        double scale = bootstrap(onLattice, rough);
        if (scale <= 0) {
            return List.of();
        }
        List<Lattice> out = List.of();
        for (int pass = 1; pass < REFINE_PASSES; pass++) {
            double spacing = FanGeometry.holeStep() * scale;
            // The two ways of reading the scale - across the rows and along them - have to agree, or
            // these rows are not one fan at one scale.
            if (Math.abs(rough - spacing) > SCALE_AGREE * rough) {
                return List.of();
            }
            List<Row> rows = rows(onLattice, spacing);
            if (rows.size() < MIN_ROWS) {
                return out;
            }
            double step = FanGeometry.depthStepAlongRow() * scale;
            List<Double> lines = pinLines(rows, spacing, step);
            if (lines.isEmpty()) {
                return out;
            }
            out = new ArrayList<>();
            for (double backPin : lines) {
                out.add(lattice(rows, backPin, step, scale));
            }
            // Re-read the scale off the rows measured at the best line's pins, where they are not
            // biased by their own tilt, and go round again.
            scale = out.getFirst().scale();
        }
        return out;
    }

    /** One line's lattice: the fan centre it implies, and the scale it was built at. */
    private static Lattice lattice(List<Row> rows, double backPin, double step, double scale) {
        double along = 0, across = 0;
        double[] at = new double[rows.size()];
        for (int j = 0; j < rows.size(); j++) {
            double pin = backPin - j * step;
            along += pin;
            at[j] = rows.get(j).acrossAt(pin);
            across += at[j];
        }
        // The centroid of the pins IS the fan centre, exactly, for every plate count - the plate
        // depths sum to zero whatever n is. That is the whole translation solve.
        double pitch = medianGap(at);
        return new Lattice(pitch > 0 ? pitch / FanGeometry.rowPitch() : scale,
                along / rows.size(), across / rows.size(), rms(rows));
    }

    /** A first scale, from the rows as their own holes place them: biased, but only by a few %. */
    private double bootstrap(List<double[]> onLattice, double rough) {
        List<Row> rows = rows(onLattice, rough);
        if (rows.size() < MIN_ROWS) {
            return -1;
        }
        double[] across = new double[rows.size()];
        for (int j = 0; j < rows.size(); j++) {
            across[j] = rows.get(j).across();
        }
        double pitch = medianGap(across);
        return pitch <= 0 ? -1 : pitch / FanGeometry.rowPitch();
    }

    /**
     * Every pin line the rows will support, best first: each is the back-most row's pin, from which
     * the depth step names every other. Empty when nothing a lock's worth of rows agree on exists.
     *
     * <p><b>It returns several on purpose, and that is not hedging.</b> No row can be sure of its
     * own pin - an unbroken chain of six holes is a plate at -3 or one at +3, and there is nothing
     * in the row to say which - so each offers every slot it cannot rule out and the fan is supposed
     * to decide. It usually does. But the rows have gaps in them (the wells are ~90% of the holes),
     * and a row with gaps offers more slots, and enough of those and a <b>second</b> line can be
     * drawn through one candidate per row that is just as collinear as the true one. Measured, the
     * best-supported line is sometimes exactly one slot off the truth - which is the worst possible
     * error, because a one-slot shift still looks like a lattice.
     *
     * <p>Nothing in the geometry breaks that tie. What breaks it is the pixels: the reader, at each
     * pose in turn, and a lock is only read at the true one. So the tie is handed to it rather than
     * guessed at here.
     */
    private List<Double> pinLines(List<Row> rows, double spacing, double step) {
        List<double[]> lines = new ArrayList<>(); // {backPin, support}
        for (int j = 0; j < rows.size(); j++) {
            for (double pin : pins(rows.get(j), spacing)) {
                int agree = 0;
                for (int k = 0; k < rows.size(); k++) {
                    double want = pin - (k - j) * step;
                    for (double other : pins(rows.get(k), spacing)) {
                        if (Math.abs(other - want) <= SAME_PIN * spacing) {
                            agree++;
                            break;
                        }
                    }
                }
                double back = pin + j * step; // anchored on the back row, so a line is named once
                if (agree >= MIN_ROWS && !isNear(names(lines), back, spacing)) {
                    lines.add(new double[] {back, agree});
                }
            }
        }
        lines.sort(Comparator.comparingDouble((double[] a) -> -a[1]));
        List<Double> out = new ArrayList<>();
        for (double[] line : lines.subList(0, Math.min(MAX_PIN_LINES, lines.size()))) {
            out.add(line[0]);
        }
        return out;
    }

    private static List<Double> names(List<double[]> lines) {
        List<Double> out = new ArrayList<>();
        for (double[] line : lines) {
            out.add(line[0]);
        }
        return out;
    }

    /** The median gap in an ascending run - robust to a row the fan does not own. */
    private static double medianGap(double[] across) {
        if (across.length < 2) {
            return -1;
        }
        double[] gaps = new double[across.length - 1];
        for (int i = 1; i < across.length; i++) {
            gaps[i - 1] = across[i] - across[i - 1];
        }
        Arrays.sort(gaps);
        return gaps[gaps.length / 2];
    }

    /**
     * Every place this row's pin could be: a slot on the row's own lattice, within reach of all its
     * holes, with <b>no hole on it</b>.
     *
     * <p>The obvious version of this is wrong, and it is worth saying how, because it looks right.
     * A plate shows six of seven slots and the pin fills the seventh, so the pin is the row's double
     * gap - and where the chain has one, that is that. Except the wells are only ~90% of the holes,
     * and <b>a missing hole makes exactly the same double gap</b>. Measured on
     * {@code 3840x2160/front-plate-sweep/step-0}, plate 0's 1.83-spacing gap is a hole that was
     * never detected, at 2953; the real pin is at 3140, two slots past the chain's far end. Reading
     * the gap as the pin put that row's proposal 180px out. And a five-hole chain with no gap at all
     * - the other thing missing recall does - proposed nothing, so the row abstained. Between them
     * these left three rows supporting the truth where four were needed, and the fit was refused on
     * a frame it had otherwise solved.
     *
     * <p>So no row is asked to be sure. Each offers every slot it cannot rule out, and the fan
     * decides: the pins lie on one line along the depth step, so a proposal in any row predicts the
     * pin of every other, and the one the rest agree with is the pin. A row that is wrong about
     * itself simply fails to be agreed with.
     */
    private static List<Double> pins(Row row, double spacing) {
        List<Double> along = row.along();
        List<Double> out = new ArrayList<>();
        // Anchor the lattice on each hole in turn and step out to every slot the pin could occupy.
        // Anchoring on each of them rather than on one is what keeps a missing hole from shifting
        // the whole lattice: the true pin is a whole number of slots from every real hole.
        for (double anchor : along) {
            for (int m = -FanGeometry.HOLES_PER_PLATE; m <= FanGeometry.HOLES_PER_PLATE; m++) {
                double slot = anchor + m * spacing;
                if (!couldBePin(along, slot, spacing) || isNear(out, slot, spacing)) {
                    continue;
                }
                out.add(slot);
            }
        }
        return out;
    }

    /**
     * True when a pin at {@code slot} would explain this row: no hole sits on it, and every hole it
     * does have is within the seven slots a plate spans.
     */
    private static boolean couldBePin(List<Double> along, double slot, double spacing) {
        for (double hole : along) {
            double slots = Math.abs(hole - slot) / spacing;
            if (slots < 1 - SAME_PIN || slots > FanGeometry.HOLES_PER_PLATE + SAME_PIN) {
                return false; // a hole on the pin, or a hole too far away to be this plate's
            }
        }
        return true;
    }

    /** True when {@code slot} is already offered, so the same proposal is not made twice. */
    private static boolean isNear(List<Double> offered, double slot, double spacing) {
        for (double at : offered) {
            if (Math.abs(at - slot) <= SAME_PIN * spacing) {
                return true;
            }
        }
        return false;
    }

    /**
     * How far, rms, a row's wells sit from its own straight line - the fit's residual, and a real
     * quality signal rather than a restatement of the gates.
     *
     * <p>It is measured <b>across</b> the row and not along it, because only across is the row
     * straight: perspective runs the spacing 41-54px along a single row, so wells never sit on an
     * even comb and the distance from one would say nothing. Across, the measured rows are straight
     * to 1.2-2.0px, which is what makes this worth asserting.
     */
    private static double rms(List<Row> rows) {
        double sum = 0;
        int n = 0;
        for (Row row : rows) {
            for (double across : row.spread()) {
                sum += across * across;
                n++;
            }
        }
        return n == 0 ? 0 : Math.sqrt(sum / n);
    }

    // --- the account -----------------------------------------------------------------------------

    private static String account(Plane p, int wells, int rows, String verdict) {
        return String.format(Locale.ROOT,
                "locator: %d stable dark wells in %dx%d (worked at 1/%d), %d hole row(s).%n"
                + "         No lattice: %s",
                wells, p.w * p.by, p.h * p.by, p.by, rows, verdict);
    }

    private static String found(Plane p, ViewMapping pose, Lattice lattice, int wells, int rows) {
        return String.format(Locale.ROOT,
                "locator: %d stable dark wells in %dx%d (worked at 1/%d), %d hole row(s).%n"
                + "         Lattice found at scale %.4f, fan centre (%.1f, %.1f), rms %.2fpx - "
                + "and the reader confirms a lock there.",
                wells, p.w * p.by, p.h * p.by, p.by, rows, pose.scale(),
                pose.x(FanGeometry.referenceFanCenter()[0]), pose.y(FanGeometry.referenceFanCenter()[1]),
                lattice.rms);
    }
}
