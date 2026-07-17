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

    /** Long side the frame is worked at, which is what makes the rule's thickness a constant. */
    private static final int WORK_LONG_SIDE = 1280;
    /**
     * The ridge's reach, in worked px: a pixel is on the rule if it is brighter than the pixels this
     * far above and below. The rule is ~10 reference px thick at 4K and ~2 at 800x600 - but 2 to 3.3
     * <b>worked</b> px at every resolution, which is the point of working at a fixed size.
     */
    private static final int RIDGE_REACH = 4;
    /**
     * Worked px of dropout a run may jump. The diamond ornament at the rule's centre breaks the
     * ridge, and a run that stops there measures half a rule and a wrong scale by a factor of two.
     */
    private static final int RIDGE_GAP = 8;

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
     * <p>The rule fixes the scale to a tenth of a percent but its <b>centre</b> only to about 8px:
     * it tapers, so which pixel is its last is a little arbitrary, and the taper is not symmetric.
     * The reader needs better than that - a pin 14px off puts the next hole 34px away, under the
     * walk's 36px floor, and the row stops adding up. So the pose is nudged until it reads.
     *
     * <p>This is a search the lattice locator could never have run: it is only sound because the
     * rule has already said which slot is which. Half a hole spacing is 24 reference px, and the
     * whole search is narrower than that, so nothing in it can land on the wrong slot. The reader
     * cannot tell one slot from the next - but that is not what it is being asked.
     */
    private static final int NUDGE = 12, NUDGE_STEP = 4;

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
            ViewMapping read = pose(plane, rule);
            if (read == null) {
                continue;
            }
            // The counter panel first: it is far cheaper than a read, and it is what tells the
            // overlay from a beam in the room.
            if (Tone.estimate(frame, read).isGuess() || !onFrame(frame, read)) {
                continue;
            }
            for (ViewMapping pose : nudges(read)) {
                if (++tried > MAX_POSES) {
                    break;
                }
                if (reads(frame, pose)) {
                    double moved = Math.hypot(pose.ox() - read.ox(), pose.oy() - read.oy())
                            / pose.scale();
                    return new Fit(pose, read.scale(), moved,
                            found(plane, pose, rules.size(), moved));
                }
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
            int lo = -1, hi = -1, runLo = -1, runHi = -1, gap = 0;
            for (int x = 0; x < p.w; x++) {
                int v = p.lum[y * p.w + x];
                if (v > p.lum[(y - RIDGE_REACH) * p.w + x] && v > p.lum[(y + RIDGE_REACH) * p.w + x]) {
                    runLo = runLo < 0 ? x : runLo;
                    runHi = x;
                    gap = 0;
                } else if (runLo >= 0 && ++gap > RIDGE_GAP) {
                    if (runHi - runLo > hi - lo) {
                        lo = runLo;
                        hi = runHi;
                    }
                    runLo = -1;
                }
            }
            if (runLo >= 0 && runHi - runLo > hi - lo) {
                lo = runLo;
                hi = runHi;
            }
            if (lo >= 0) {
                out.add(new double[] {y, lo, hi});
            }
        }
        out.sort(Comparator.comparingDouble(a -> a[1] - a[2])); // longest first
        return out.subList(0, Math.min(MAX_CANDIDATES, out.size()));
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

    /** The pose, and the small neighbourhood around it the rule cannot resolve. See {@link #NUDGE}. */
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
     * The lock must <b>read</b> at the pose, not merely be counted there: every row resolved, no
     * {@link LockModel#UNKNOWN}. Counting is not enough - measured, {@code detectPlateCount} alone
     * accepts a pose 34px out, because a row counts as a plate on a popped pin in lit steel whose
     * holes never resolved.
     *
     * <p>Always the {@link LatticeReader}, whatever {@code --reader} the run is using: this is a
     * question about <b>where the lock is</b>, and the tone-free reader is strictly the better
     * instrument for it - a frame whose colours the calibrated reader cannot handle is exactly the
     * kind of frame that got here.
     */
    private static boolean reads(BufferedImage frame, ViewMapping pose) {
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
