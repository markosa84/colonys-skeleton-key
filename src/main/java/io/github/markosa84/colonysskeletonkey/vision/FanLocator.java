package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Finds the lock by the minigame's own HUD, and reports where it must be - rather than being told.
 *
 * <p>Everything else in this package is handed a {@link ViewMapping} derived from the game window's
 * measured rectangle, and that measurement is a guess which <b>cannot fail loudly</b>. Get the
 * rectangle wrong and the reader scans a box the lock is not in, finds nothing, and the player is
 * told "no lock detected" over a screenshot in which the lock is plainly visible. It is the single
 * biggest real failure class this tool has had: it shipped for a year measuring the display and
 * assuming the game filled it (see {@link Viewport}, and {@code WindowedGameTest}).
 *
 * <p>So this class asks the pixels instead. It is the second opinion, and it is only ever consulted
 * when the first one has already failed.
 *
 * <h2>Why the HUD's rule, and not the lock</h2>
 * The obvious thing to look for is the lock: 24-42 dark holes on one two-basis lattice at a fixed
 * angle is a strong signature, and nothing in a room is that by accident. That was tried, at length,
 * and it does not work - <b>because a lattice is periodic</b>. Every measurement it offers is
 * ambiguous by a lattice vector: a pose one hole spacing out fits the holes exactly as well as the
 * true one, and no amount of geometry inside the lock can break the tie. Worse, the reader cannot
 * break it either. {@code LatticeReader.walk} bridges a missing hole with a double step - which is
 * the fix for the {@code 6p-gap-shadow} bug and the reason the reader is robust - so a pose shifted
 * by about a slot still walks six holes and <b>reads the lock, with wrong offsets, reporting
 * nothing amiss</b>. Measured: strengthening the confirmation from "counts a lock" to "reads every
 * row" changed the worst pose error by nothing at all.
 *
 * <p>The HUD has no such problem. The minigame draws an opaque overlay - a title, a rule under it,
 * the difficulty pips, the key legend, the lockpick counter - at <b>fixed reference positions</b>,
 * over whatever room the lock is in. The rule under the title is the best landmark on it:
 *
 * <ul>
 *   <li><b>Aperiodic.</b> There is one of it. A pose that fits it is the pose; there is no
 *       "one over" to be confused with, which is the whole reason to prefer it to the lock.</li>
 *   <li><b>Long.</b> {@link #RULE_LEN} reference px, against a hole spacing of 48 - so it is a
 *       ruler, and reading it to a pixel is a scale to a tenth of a percent.</li>
 *   <li><b>Fixed.</b> Measured identical - reference y {@value #RULE_Y}, centred on x
 *       {@value #RULE_MID}, {@value #RULE_LEN} long - across 4/5/6/7-plate locks, four rooms, both
 *       titles ("OPEN CHEST" and "OPEN DOOR"), the whole gamma slider and every display mode. It is
 *       HUD: the room cannot touch it.</li>
 *   <li><b>Centred on the lock.</b> Its midpoint is reference x 3089.5, and the fan's centre is
 *       3090. The overlay is built around the lock, so the rule's centre <i>is</i> the fan's.</li>
 * </ul>
 *
 * <h2>Order statistics only - no level means anything here</h2>
 * The rule is found as a horizontal <b>ridge</b>: a pixel brighter than the pixels a little above
 * and below it. That is a pure comparison, so a monotone tone map - which is what both the gamma LUT
 * and an HDR tonemap are - moves nothing here. There is no {@link Tone} in the search and there must
 * not be: the frames this exists to rescue are exactly the ones a tone curve gets wrong. An earlier
 * version thresholded at a percentile instead and failed on its own fixtures, because the rule
 * tapers at its ends and any single cut lops them off.
 *
 * <h2>It proposes; two independent things dispose</h2>
 * A rule candidate is only a proposal - the room has long bright edges too (a beam in a reporter's
 * frame outranks the real rule). A pose is returned only once <b>both</b> agree:
 * <ul>
 *   <li>the <b>lockpick counter</b> is where the pose says it is - {@link Tone#estimate} finds the
 *       panel's two plateaus there rather than scenery. Opaque UI, fixed spot, aperiodic;</li>
 *   <li>and {@link LatticeReader} <b>reads</b> a lock at it - every row resolved, no
 *       {@link LockModel#UNKNOWN}.</li>
 * </ul>
 * Neither is sufficient alone, and the second one is only trustworthy <i>because</i> the first
 * fixed the pose to within half a hole spacing: the reader cannot tell one slot from the next, but
 * it can certainly tell the lock from the wall.
 */
public final class FanLocator {

    // --- The rule, measured. See the class doc; these are what the RIDGE reads, on the corpus.

    /** The rule's row, in reference px. Measured 285-295 across the corpus; the spread is its own. */
    private static final double RULE_Y = 291.0;
    /** Where the rule is centred, in reference px - and the fan's own centre is 3090. */
    private static final double RULE_MID = 3089.5;
    /**
     * The rule's length in reference px, <b>as this detector reads it</b> - not as a person reads it
     * off the screen, which is 695. The ridge runs a little past each end, where the rule tapers
     * into the background, and it does so by the same amount every time: measured 708-712 over the
     * whole corpus and both reporters' HDR dumps. Calibrating the constant to the detector rather
     * than to the eye is worth 2.3% of scale, which is the difference between the reader reading the
     * lock at the pose and not.
     */
    private static final double RULE_LEN = 710.0;

    // --- The lockpick counter's white panel, measured. The rule finds the HUD; this places it.

    /**
     * The panel's own white box, in reference px - measured (3114, 1620) to (3165, 1671), the same
     * on every frame in the corpus, at every gamma, and on both reporters' HDR dumps.
     *
     * <p>Note this is <b>not</b> {@code GameScreen.picksBox} (3104, 1616, 72x56), which is a box
     * drawn <i>around</i> the panel with room to spare, because all {@link Tone} wants from it is a
     * histogram. What is measured here is the white itself, and its edges are razor sharp.
     */
    private static final double PANEL_X0 = 3114.0, PANEL_Y0 = 1620.0, PANEL_X1 = 3165.0;
    private static final double PANEL_MID = (PANEL_X0 + PANEL_X1) / 2;

    /**
     * The distance from the rule to the panel, in reference px - and the reason the two are used
     * together rather than either alone.
     *
     * <p>The rule is a fine ruler and a poor anchor: it tapers, and not symmetrically, so which
     * pixel is its last is a little arbitrary and its centre is only good to about 8px. The panel is
     * the opposite - 51px is far too short to read a scale off, but its edges are sharp and its
     * position is good to a pixel. So each is asked only what it is good at: the panel says
     * <b>where</b>, and the gap between them says <b>how big</b>. That gap is nearly twice the
     * rule's own length, so it is the better ruler as well.
     */
    private static final double BASELINE = PANEL_Y0 - RULE_Y;

    /** How far the panel is hunted for, either way, around where the rule says it should be. */
    private static final int PANEL_HUNT = 70;
    /** The percentile of the hunting window the panel's white stands above. */
    private static final double PANEL_BRIGHT = 0.90;
    /** How far the two independent scales - the rule's length, and the baseline - may disagree. */
    private static final double SCALE_AGREE = 0.06;
    /** How far the panel's measured width may sit from what the solved scale says it should be. */
    private static final double PANEL_SIZE_TOL = 0.20;

    /** Long side the frame is worked at, which is what makes the rule's thickness a constant. */
    private static final int WORK_LONG_SIDE = 1280;
    /**
     * The ridge's reach, in worked px: a pixel is on the rule if it is brighter than the pixels this
     * far above and below. The rule is ~10 reference px thick at 4K and ~2 at 800x600 - but 2 to 3.3
     * <b>worked</b> px at every resolution, which is the point of working at a fixed size.
     */
    private static final int RIDGE_REACH = 4;
    /**
     * Worked px of dropout a run may jump.
     *
     * <p><b>Tight, and it has to be.</b> This number cannot be asked to bridge the diamond ornament
     * at the rule's centre - that is what {@link #MERGE} is for - because the room is full of faint
     * horizontal ridges and every px of slack here chains the rule into them. Measured on the
     * corpus: at 8 the run runs 100px past each end of the rule and reads 912 reference px where the
     * rule is 710, which is a 28% scale error and a pose that finds no lockpick counter at all. At 3
     * it reads 699-712 on nine frames in twelve.
     */
    private static final int RIDGE_GAP = 3;

    /**
     * How far apart two runs on one row may sit and still be one thing, as a fraction of the shorter
     * of them.
     *
     * <p>The rule is drawn with a diamond ornament at its centre, and on a dark gamma - or at a
     * small resolution - the ornament breaks the ridge in two. Each half then measures ~355
     * reference px, which is half a rule, which is a scale wrong by a factor of two. Widening
     * {@link #RIDGE_GAP} to bridge it lets the room in; this joins the halves without doing that,
     * because the test is <b>relative</b>: two pieces are one thing when the gap between them is
     * small compared to the pieces themselves. The rule's halves are ~118 worked px each with ~7
     * between them, and join. A room's two beams are 100 apart with 300 between them, and do not.
     * Nor does dense texture: three-pixel ridges two pixels apart are not each other's halves.
     */
    private static final double MERGE = 0.15;

    /** Scales worth believing: below this the lock is unreadable anyway, above it there is no display. */
    private static final double MIN_SCALE = 0.15, MAX_SCALE = 1.6;

    /** How many rule candidates are examined, best first. The room's beams are long too. */
    private static final int MAX_CANDIDATES = 40;
    /** Poses tried per candidate; each costs a read, and this runs once per F8 at most. */
    private static final int MAX_POSES = 200;

    /**
     * How far the search nudges a pose around what the rule says, in reference px, and in what
     * steps.
     *
     * <p>The two landmarks between them leave a few pixels of slack, and the reader is unforgiving
     * of it: a pin 14px off puts the next hole 34px away, under the walk's 36px floor, and the row
     * stops adding up. So the pose is nudged until it reads.
     *
     * <p>This is a search the lattice locator could never have run: it is only sound because the HUD
     * has already said which slot is which. Half a hole spacing is 24 reference px, and the whole
     * search is narrower than that, so nothing in it can land on the wrong slot. The reader cannot
     * tell one slot from the next - but that is not what it is being asked.
     *
     * <p><b>Do not widen it.</b> Measured: at 16 the locator finds three more locks and misreads
     * nine, where at 8 it misreads one. A wider search does not find the truth further out - it
     * manufactures falsehoods nearer in, because the further it wanders the more likely it is to
     * meet a pose at which a <i>smaller</i> fan reads consistently. The answer to a pose that is out
     * by more than this is a better landmark, never a bigger net.
     */
    private static final int NUDGE = 8, NUDGE_STEP = 4;

    /**
     * What the locator made of one frame: a pose, or the reason there is none - and an account
     * either way, because a refusal is the interesting case and a report has to say how far it got.
     *
     * @param mapping where the lock is, or <b>null</b> when it was not found
     * @param scale   the scale the rule measured, before any nudging
     * @param nudged  how far the pose had to be moved off the rule's own reading, in reference px
     */
    public record Fit(ViewMapping mapping, double scale, double nudged, String account) {

        static Fit refused(String account) {
            return new Fit(null, 0, 0, account);
        }

        /** True when {@link #mapping()} is an answer rather than a null. */
        public boolean found() {
            return mapping != null;
        }
    }

    /**
     * Where the lock in this frame is, or why that could not be said. The frame is the game's
     * <b>view</b>, as everything else in this package takes it, and the pose is view-local like
     * every other mapping here.
     */
    public Fit locate(BufferedImage frame) {
        Plane plane = Plane.of(frame);
        List<double[]> rules = rules(plane);
        if (rules.isEmpty()) {
            return Fit.refused(account(plane, 0, 0,
                    "no horizontal line long enough to be the minigame's title rule. Either this is "
                            + "not the lockpicking view at all, or its overlay is not being drawn."));
        }
        int tried = 0;
        for (double[] rule : rules) {
            ViewMapping rough = pose(plane, rule);
            if (rough == null || !onFrame(frame, rough)) {
                continue;
            }
            // The counter panel: the second landmark, and the one that actually places the HUD.
            // Also the thing that tells the overlay from a beam in the room - a beam has no white
            // panel 1329px under it - and it costs far less than a read.
            ViewMapping read = place(frame, rough, plane.toFrame(rule[0]));
            if (read == null || !onFrame(frame, read)) {
                continue;
            }
            // Every nudge that reads, and then the LARGEST lock among them - not the nearest.
            //
            // "A smaller fan is not a smaller lock" is this codebase's own hardest-won rule, and it
            // applies here for the same reason it applies in the reader: the fans of n and n+2 share
            // a lattice, so a pose that is a little out can read a real 5-plate lock as a consistent
            // 4-plate one - walk and all, six holes a row, pop agreeing with the walk. Measured, that
            // was the last 7 misreads, and every one of them was a 4 where the truth was 5. Taking
            // the first pose that reads means taking whichever the search happened to reach first;
            // taking the largest is the same choice detectPlateCount already makes when the lattices
            // overlap, and it is right for the same reason.
            ViewMapping best = null;
            int most = 0;
            for (ViewMapping pose : nudges(read)) {
                if (++tried > MAX_POSES) {
                    break;
                }
                int n = reads(frame, pose);
                if (n > most) {
                    most = n;
                    best = pose;
                }
            }
            if (best != null) {
                double moved = Math.hypot(best.ox() - read.ox(), best.oy() - read.oy())
                        / best.scale();
                return new Fit(best, read.scale(), moved, found(plane, best, rules.size(), moved));
            }
        }
        return Fit.refused(account(plane, rules.size(), tried,
                "found " + rules.size() + " long horizontal line(s), but at none of them is there "
                        + "both a lockpick counter and a readable lock. A line is not an overlay."));
    }

    // --- the rule --------------------------------------------------------------------------------

    /**
     * The frame as a luminance plane, box-averaged down so its long side is about
     * {@link #WORK_LONG_SIDE}.
     *
     * <p>The downscale is not only for speed. It <b>normalises the rule's thickness</b>: 10 reference
     * px at 4K and 2 at 800x600 both come out 2-3.3 px here, so one ridge reach reads the rule at
     * every resolution, and the scale never has to be known before it is measured.
     *
     * <p>Each cell holds the <b>sum</b> of its pixels, not their mean, and that is not a shortcut -
     * it is the whole dark end of the gamma slider. Everything downstream compares these values and
     * never scales them, so the divide buys nothing and its rounding costs the frames that can least
     * afford it: at gamma 1.2 the game squeezes the entire range into observed 0..128, and the rule
     * stands only two or three levels above what it is drawn on. Divide by nine and round, and that
     * margin becomes a tie - the ridge test needs strictly brighter, ties are not brighter, and the
     * rule stops existing. Keeping the sum keeps nine times the resolution, for nothing.
     *
     * @param by the integer factor the frame was shrunk by, so a find can be put back
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
                    lum[y * w + x] = sum;
                }
            }
            return new Plane(lum, w, h, by);
        }

        /** A worked coordinate, back in the frame's own. */
        double toFrame(double at) {
            return (at + 0.5) * by - 0.5;
        }
    }

    /**
     * Every long horizontal ridge in the plane, longest first, as {@code {y, lo, hi}} in worked
     * coordinates: the rule is one of these, and so is every beam in the room.
     */
    List<double[]> rules(Plane p) {
        List<double[]> out = new ArrayList<>();
        for (int y = RIDGE_REACH; y < p.h - RIDGE_REACH; y++) {
            List<double[]> runs = merge(runs(p, y));
            for (double[] run : runs) {
                out.add(new double[] {y, run[0], run[1]});
            }
        }
        out.sort(Comparator.comparingDouble(a -> a[1] - a[2])); // longest first
        return out.subList(0, Math.min(MAX_CANDIDATES, out.size()));
    }

    /** Every maximal ridge run on one row, as {@code {lo, hi}}, left to right. */
    private static List<double[]> runs(Plane p, int y) {
        List<double[]> runs = new ArrayList<>();
        int lo = -1, hi = -1, gap = 0;
        for (int x = 0; x < p.w; x++) {
            int v = p.lum[y * p.w + x];
            if (v > p.lum[(y - RIDGE_REACH) * p.w + x] && v > p.lum[(y + RIDGE_REACH) * p.w + x]) {
                lo = lo < 0 ? x : lo;
                hi = x;
                gap = 0;
            } else if (lo >= 0 && ++gap > RIDGE_GAP) {
                runs.add(new double[] {lo, hi});
                lo = -1;
            }
        }
        if (lo >= 0) {
            runs.add(new double[] {lo, hi});
        }
        return runs;
    }

    /** Joins the runs that are pieces of one thing. See {@link #MERGE}. */
    private static List<double[]> merge(List<double[]> runs) {
        List<double[]> out = new ArrayList<>();
        for (double[] run : runs) {
            if (!out.isEmpty()) {
                double[] last = out.getLast();
                double between = run[0] - last[1];
                double shorter = Math.min(last[1] - last[0], run[1] - run[0]);
                if (between <= MERGE * shorter) {
                    last[1] = run[1];
                    continue;
                }
            }
            out.add(new double[] {run[0], run[1]});
        }
        return out;
    }

    /** The pose a rule candidate implies, or null when it implies no believable one. */
    private static ViewMapping pose(Plane p, double[] rule) {
        double lo = p.toFrame(rule[1]), hi = p.toFrame(rule[2]);
        double scale = (hi - lo) / RULE_LEN;
        if (scale < MIN_SCALE || scale > MAX_SCALE) {
            return null;
        }
        return new ViewMapping(scale, (lo + hi) / 2 - RULE_MID * scale,
                p.toFrame(rule[0]) - RULE_Y * scale);
    }

    /**
     * The pose the rule and the counter panel fix between them, or null when there is no panel where
     * the rule says one should be.
     *
     * <p>The rule alone gives a pose good to a few percent, which is enough to know where to look
     * for the panel and not much else. The panel then does the placing: its edges are sharp, so its
     * box is good to a pixel where the rule's tapered ends are good to eight, and the long gap up to
     * the rule ({@link #BASELINE}) is a better ruler than the rule itself. The rule's own length is
     * kept as an independent second opinion on the scale, and the two must agree.
     */
    private static ViewMapping place(BufferedImage frame, ViewMapping rough, double ruleY) {
        double[] panel = panel(frame, rough);
        if (panel == null) {
            return null;
        }
        double scale = (panel[1] - ruleY) / BASELINE;
        if (scale < MIN_SCALE || scale > MAX_SCALE
                || Math.abs(scale - rough.scale()) > SCALE_AGREE * rough.scale()) {
            return null; // the two rulers disagree, so this is not one overlay
        }
        // And the panel has to be the size the scale says it is. This is what stops a bright blob
        // that merely happens to sit near where an impostor rule predicts a panel: it is free (the
        // box is already measured), and it is independent of both rulers, being a width where they
        // are a length along x and a distance in y.
        double width = panel[2] - panel[0];
        if (Math.abs(width - (PANEL_X1 - PANEL_X0) * scale) > PANEL_SIZE_TOL * width) {
            return null;
        }
        return new ViewMapping(scale, (panel[0] + panel[2]) / 2 - PANEL_MID * scale,
                panel[1] - PANEL_Y0 * scale);
    }

    /**
     * The counter panel's white box near where {@code rough} says it is, as {@code {x0, y0, x1}}:
     * the biggest bright blob in the neighbourhood. The panel is the brightest flat thing on the
     * screen and its surroundings are the lock's own dark casing, so "biggest and bright" finds it
     * without a level - the cut is a percentile of the window, which no tone map moves.
     */
    private static double[] panel(BufferedImage frame, ViewMapping rough) {
        int x0 = clamp((int) rough.x(PANEL_X0 - PANEL_HUNT), frame.getWidth());
        int y0 = clamp((int) rough.y(PANEL_Y0 - PANEL_HUNT), frame.getHeight());
        int x1 = clamp((int) rough.x(PANEL_X1 + PANEL_HUNT), frame.getWidth());
        int y1 = clamp((int) rough.y(PANEL_Y0 + PANEL_HUNT), frame.getHeight());
        int w = x1 - x0, h = y1 - y0;
        if (w < 4 || h < 4) {
            return null;
        }
        int[] lum = new int[w * h];
        int[] hist = new int[256];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int l = LockReader.luminance(frame.getRGB(x0 + x, y0 + y));
                lum[y * w + x] = l;
                hist[l]++;
            }
        }
        int cut = percentile(hist, (long) w * h, PANEL_BRIGHT);
        boolean[] seen = new boolean[w * h];
        int[] stack = new int[w * h];
        double[] best = null;
        int bestArea = 0;
        for (int seed = 0; seed < w * h; seed++) {
            if (seen[seed] || lum[seed] < cut) {
                continue;
            }
            int sp = 0;
            stack[sp++] = seed;
            seen[seed] = true;
            int area = 0, minX = w, maxX = -1, minY = h;
            while (sp > 0) {
                int p = stack[--sp];
                int px = p % w, py = p / w;
                area++;
                minX = Math.min(minX, px);
                maxX = Math.max(maxX, px);
                minY = Math.min(minY, py);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = px + dx, ny = py + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                            continue;
                        }
                        int q = ny * w + nx;
                        if (!seen[q] && lum[q] >= cut) {
                            seen[q] = true;
                            stack[sp++] = q;
                        }
                    }
                }
            }
            if (area > bestArea) {
                bestArea = area;
                best = new double[] {x0 + minX, y0 + minY, x0 + maxX};
            }
        }
        return best;
    }

    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(max, v));
    }

    /** The luminance the given fraction of a window's pixels fall below. */
    private static int percentile(int[] hist, long total, double p) {
        long want = (long) Math.ceil(p * total), seen = 0;
        for (int v = 0; v < hist.length; v++) {
            seen += hist[v];
            if (seen >= want) {
                return v;
            }
        }
        return hist.length - 1;
    }

    /** The pose, and the small neighbourhood around it the landmarks cannot resolve. */
    private static List<ViewMapping> nudges(ViewMapping read) {
        List<ViewMapping> out = new ArrayList<>();
        for (int dx = -NUDGE; dx <= NUDGE; dx += NUDGE_STEP) {
            for (int dy = -NUDGE; dy <= NUDGE; dy += NUDGE_STEP) {
                out.add(new ViewMapping(read.scale(), read.ox() + dx * read.scale(),
                        read.oy() + dy * read.scale()));
            }
        }
        // Nearest first: the rule's own reading is usually right, and the nudge is a last resort.
        out.sort(Comparator.comparingDouble(m -> Math.hypot(m.ox() - read.ox(), m.oy() - read.oy())));
        return out;
    }

    // --- the two disposers -----------------------------------------------------------------------

    /**
     * The lock must <b>be walked</b> at the pose: every row, all six holes, hole to hole.
     *
     * <p>The two weaker versions of this were both tried, and both were measured accepting poses
     * that read the lock <i>wrongly</i> - which is the one outcome worse than not finding it, since
     * the session would then drive a wrong model into the walls:
     * <ul>
     *   <li><b>{@code detectPlateCount ∈ 4..7}</b> alone accepts a pose 34px out. A row counts as a
     *       plate on {@code (walked == 6 || popped) && lit >= LIT_PLATE}, so a popped pin in lit
     *       steel carries a row whose holes never resolved.</li>
     *   <li><b>and no {@link LockModel#UNKNOWN} in the state</b> is barely stronger, because
     *       {@code RowFit.offset()} short-circuits a popped row to 0 <i>without walking it</i>. A
     *       pose where every row happens to read "popped" therefore resolves every row and reports
     *       a lock at {@code [0, 0, 0, 0, 0, 0]} - a solved lock, out of a wrong pose. Measured, 81
     *       of 233 located poses did exactly this.</li>
     * </ul>
     *
     * <p>So <b>both</b> of the reader's signals are asked, and they must agree on every row:
     * <ul>
     *   <li>the <b>walk</b> - all six holes, pin to hole to hole at a measured spacing - which a
     *       pose off by a fraction of a slot cannot make add up;</li>
     *   <li>and the <b>pop</b>, which must say "centred" for exactly the row the walk puts at slot
     *       0, and for no other.</li>
     * </ul>
     *
     * <p>That last clause is what closes the hole, and it is worth seeing why it is strong. The two
     * signals are <b>physically different things</b>: the pop is the brass pin standing up out of
     * the plate, and the walk is the row of holes cut in it. Nothing about a wrong pose makes them
     * agree - it moves the pin column onto a hole and the plate reads popped when the walk says it
     * sits at 3. Measured, that single disagreement was 59 of the misreads: poses that walked all
     * six holes on every row and still reported {@code [3, 1, 0, 0, 3]} where the truth is
     * {@code [3, 1, 2, 0, 3]}, because {@code RowFit.offset()} lets a pop overrule the walk. Only
     * the true pose has the pin standing where the holes say the middle is.
     *
     * <p>It is a strict bar, and it should be: this is a pose the tool <i>invented</i>, on a frame
     * it had already failed to read, and a wrong one costs the player lockpicks. A lock this refuses
     * is left to the reader's own machinery, which is built to be generous; the locator's job is
     * only to say where to look, and it should only say so when it is sure.
     *
     * <p>Always the {@link LatticeReader}, whatever {@code --reader} the run is using: this is a
     * question about <b>where the lock is</b>, and the tone-free reader is strictly the better
     * instrument for it - a frame whose colours the calibrated reader cannot handle is exactly the
     * kind of frame that got here.
     */
    private static int reads(BufferedImage frame, ViewMapping pose) {
        LatticeReader reader = new LatticeReader(pose, Tone.estimate(frame, pose));
        int n = reader.detectPlateCount(frame);
        if (n < LockModel.MIN_PLATES || n > LockModel.MAX_PLATES) {
            return 0;
        }
        for (LatticeReader.RowFit row : reader.rows(frame, n)) {
            if (row.walked() != FanGeometry.HOLES_PER_PLATE) {
                return 0;
            }
            if (row.popped() != (row.slot() == LockModel.MAX_OFFSET)) {
                return 0;
            }
        }
        return n;
    }

    /** True when the pose puts enough of the fan on the frame to be a lock the player can see. */
    private static boolean onFrame(BufferedImage frame, ViewMapping pose) {
        Rectangle fan = new FanGeometry(pose).fanCropScreenBounds(LockModel.MAX_PLATES);
        Rectangle on = fan.intersection(new Rectangle(0, 0, frame.getWidth(), frame.getHeight()));
        return !on.isEmpty() && (double) on.width * on.height >= 0.85 * fan.width * fan.height;
    }

    // --- the account -----------------------------------------------------------------------------

    private static String account(Plane p, int candidates, int tried, String verdict) {
        return String.format(Locale.ROOT,
                "locator: %dx%d worked at 1/%d, %d long horizontal line(s), %d pose(s) tried.%n"
                + "         Not found: %s",
                p.w * p.by, p.h * p.by, p.by, candidates, tried, verdict);
    }

    private static String found(Plane p, ViewMapping pose, int candidates, double nudged) {
        return String.format(Locale.ROOT,
                "locator: %dx%d worked at 1/%d, %d long horizontal line(s).%n"
                + "         The overlay's title rule puts the lock at scale %.4f, fan centre "
                + "(%.1f, %.1f)%s - the lockpick counter is there and the reader reads the lock.",
                p.w * p.by, p.h * p.by, p.by, candidates, pose.scale(),
                pose.x(3090.0), pose.y(798.0),
                nudged < 1 ? "" : String.format(Locale.ROOT, ", nudged %.0f ref px to read", nudged));
    }
}
