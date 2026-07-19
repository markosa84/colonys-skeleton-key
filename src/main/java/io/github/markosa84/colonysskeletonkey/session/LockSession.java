package io.github.markosa84.colonysskeletonkey.session;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.Cost;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.Move;

/**
 * One press of F8: learn the lock, then open it. Nothing is carried over from a previous press, and
 * the lock is never reset - the session starts from whatever is on screen.
 *
 * <h2>Learning by experiment</h2>
 * Moving a plate drags whichever plates it is connected to, and nothing on screen says which. So the
 * session nudges a plate and diffs the state read off the screen: every other plate that moved the
 * same direction is a Normal connection, opposite is Inverted. <b>One successful move reveals that
 * plate's entire row</b>, because connections don't cascade.
 *
 * <h2>Why a plate refuses to move, and what that means</h2>
 * A move is atomic: if the plate, or anything it drags, would leave its track then the whole move is
 * cancelled and the pick strains. Two strains break the pick, and the game slides every plate home.
 *
 * <p>So a plate that is not itself at the end of its track and still refuses to slide is not a plate
 * without connections - it is proof that it <b>has</b> one, to a plate parked at an end. Turn that
 * around and it becomes the plan: a move of {@code p} can fail for one reason only, some affected
 * plate sits at an end and would be pushed off it. Therefore <b>if every plate except {@code p} is off
 * the ends, {@code p} is guaranteed to move</b> - a one-step drag of an interior plate always stays on
 * the track. That is a condition you can check from a screenshot, and it is what makes strain-free
 * probing possible.
 *
 * <h2>Picks first, time second</h2>
 * Each move is chosen cheapest-risk-first:
 * <ol>
 *   <li><b>Free</b> - every other plate is already interior, so sliding this one cannot strain.</li>
 *   <li><b>Planned</b> - breadth-first search for moves of <i>already-probed</i> plates that clear the
 *       ends for some unprobed plate. Their rows are known, so {@link LockSolver#applyMove} proves each
 *       move legal before a key is pressed. Costs time, never a pick.</li>
 *   <li><b>Gamble</b> - when neither exists. Fewest plates at ends first, sliding toward centre
 *       so plates walk off the ends and the next move is safer.</li>
 *   <li><b>Reposition then gamble</b> ({@link #escalate}) - the last resort, and what keeps a solvable
 *       lock from reporting "stuck". Probing one interior plate can drag another to an end, where its
 *       only informative direction goes off-track and discovery dead-ends. So search the moves of
 *       already-probed plates for a configuration in which an unprobed plate can be gambled in a
 *       direction not already ruled out, go there, and gamble. Capped by {@link #MAX_GAMBLE_STRAINS}
 *       so a hard lock can never eat the inventory.</li>
 * </ol>
 * A gamble that strains is remembered and never retried while the plate that blocked it could still be
 * blocking it ({@link #isRefused}). <b>That memory survives a broken pick</b>, which is what stops the
 * reset from recreating the very gamble that just failed - the old failure mode where a hard lock ate
 * every pick in the player's inventory.
 *
 * <p><b>A strain the read says is impossible is a misread, not a refusal.</b> A slide can only strain by
 * dragging a plate off an end, so a strain with nothing at either end contradicts the geometry that made
 * the move look safe. Recording it as a refusal would wedge the run - an empty culprit set never expires
 * ({@link #isRefused}) - which, with an unblock planner that kept "freeing" an already-free plate, was
 * the endless one-step oscillation a reporter had to alt-tab out of. Instead it is counted, and enough
 * of them stops the run with the frame saved as a misread. A whole-run {@link #loopingWithoutProgress}
 * guard catches any residual no-progress cycle, and every give-up now saves a frame for the report.
 *
 * <h2>Unreadable rows (occlusion)</h2>
 * A settled state can arrive with {@link LockModel#UNKNOWN} entries: the reader refuses to guess
 * when it cannot resolve a row. (The one cause ever diagnosed live - a difficulty-4 chest whose
 * arch-gap shadow the old hole walk mistook for a seventh hole - is since fixed in the reader
 * itself and pinned by the {@code 6p-gap-shadow} test frame, so this machinery is a safety net
 * that should idle.) A diff with an unread plate could teach a silently
 * wrong connection row - the one mistake this session must never make - so it never learns from
 * one. While probing, the move is undone (the inverse of a legal move is always legal), remembered
 * as occluding <i>from that configuration</i>, and retried later from a different one. While
 * solving, the unread entries are filled from the model when it explains every visible plate, and
 * every later move keeps re-verifying. When a state arrives unreadable with no move to undo, the
 * session nudges a visible interior plate to change the geometry ({@link #recoverFull}). "Solved"
 * is never concluded from a model-filled zero: the goal is confirmed from a fresh direct read whose
 * every plate reads 0 with none UNKNOWN, so a guessed row can never declare a lock open.
 *
 * <h2>The player's skill is watched, never asked for</h2>
 * Nothing here depends on the character's {@link Skill}, and nothing here configures it. A broken
 * pick is <b>observed</b> - {@link MoveExecutor.Observation#pickBroke()} reads the remaining-lockpicks
 * counter, which changes at every level - so picks spent are counted, not estimated. At level 0 a
 * break also resets the puzzle; the session sees that and recovers. Above level 0 the lock is left
 * where it was and the run simply carries on.
 *
 * <p>The level itself falls out of the same observation: the strains a pick survived <i>is</i> the
 * character's strains-per-pick. The session reports what it saw and then forgets it, because the
 * player can train lockpicking between one lock and the next.
 */
public final class LockSession {

    /**
     * Give up after five broken picks: every lock in the game opens well inside that, so needing more
     * means the algorithm is wrong, not the lock.
     *
     * <p>Broken picks are counted from the lockpick counter, so this is a budget against something
     * seen rather than something guessed. The real defence is not to strain at all - see the
     * escalation above.
     */
    private static final int MAX_PICKS = 5;
    /** Safety valve on the unblock search (the state space is at most 7^7). */
    private static final int MAX_SEARCH_STATES = 200_000;
    /** Nudges tried per recovery before declaring a hidden row unrecoverable. */
    private static final int MAX_NUDGES = 3;
    /**
     * Contradictory strains tolerated before the frame is called misread. A slide can only strain by
     * dragging a plate off an end, so a strain the read says is impossible (nothing sat at an end) is
     * a misread, not a fact of the lock - see {@link #step}. A handful can be a transient; a run of
     * them is a reader that cannot be trusted on this frame, and the honest thing is to stop and dump.
     */
    private static final int MAX_MISREAD_STRAINS = 4;
    /**
     * Strains the last-resort reposition-and-gamble escalation ({@link #escalate}) may spend before it
     * gives up. Well under {@link #MAX_PICKS}, so a hard lock can never eat the inventory - the failure
     * mode this codebase exists to avoid - while still buying the extra reach that opens locks the
     * strain-free strategy dead-ends on. A gamble that <i>succeeds</i> costs nothing here; only a
     * failed one counts.
     */
    private static final int MAX_GAMBLE_STRAINS = 3;

    private final LockView view;
    private final CursorKeys keys;
    private final MoveExecutor mover;

    private int n;
    /** {@code conn[p]} is what moving p drags, or null while p is unprobed. */
    private Connection[][] conn;
    /** Slide attempts already known to strain. Never cleared within a run - see {@link #isRefused}. */
    private final List<Refusal> refused = new ArrayList<>();
    /** Moves whose outcome hid a row, keyed by the exact configuration they were tried from. */
    private final List<Occlusion> occluded = new ArrayList<>();
    /** Strains this run. The only quantity we can always observe. */
    private int strains;
    /**
     * Slides that actually moved a plate. Zero of them, once nothing is left to try, is the
     * signature of a lock we <b>misread</b> rather than one that is stuck - see {@link #loop}.
     */
    private int moves;
    /** Broken picks, counted off the lockpick counter. Exact at every skill level. */
    private int observedBreaks;
    /** Strains the pick now in hand has taken. Resets whenever one breaks. */
    private int strainsOnThisPick;
    /**
     * Strains the last pick took before it broke - this character's strains-per-pick, unless the
     * pick arrived already worn from an earlier lock. Zero until a break is seen.
     */
    private int strainsPerPick;
    /** True if a break also reset the puzzle, which only happens untrained. */
    private boolean breakResetThePuzzle;
    /** The lock as it now stands; entries may be {@link LockModel#UNKNOWN} until recovered. */
    private int[] cur;
    /** The solving moves still to play, head first, or null when there is no plan in hand. */
    private List<Move> plan;
    /** The configuration {@code plan}'s head applies to. Anything else and the plan is stale. */
    private int[] planFrom;
    /**
     * Strains that could not physically have happened - the read showed nothing at either end, yet the
     * slide was rejected. Each one is a misread offset; a run of them means the reader has lost this
     * (dark) frame. See {@link #step} and {@link #MAX_MISREAD_STRAINS}.
     */
    private int misreadStrains;
    /** Strains spent while escalating, a whole-run budget capped at {@link #MAX_GAMBLE_STRAINS}. */
    private int gambleStrains;
    /**
     * True while working through a reposition-then-gamble escalation ({@link #escalate}), so the gamble
     * it sets up has its strain charged to {@link #gambleStrains}. Cleared the moment normal discovery
     * resumes - a free or planned move, or a plate newly probed.
     */
    private boolean inEscalation;
    /** Row corrections seen ({@link #learn} folding a different row over a wrong one); a progress signal. */
    private int corrections;
    /**
     * Configurations visited since the last new fact, to catch any move loop that makes no progress.
     * Cleared whenever the run learns something ({@link #knowledgeSignature} changes); a repeat within
     * one such streak is a livelock, and the run stops instead of spinning until the game loses focus.
     */
    private final Set<Long> loopGuard = new HashSet<>();
    private long lastKnowledge;
    /**
     * A verbose, machine-and-human-readable trace of every move, kept out of the console and written
     * only to the per-F8 log file a bug report should carry - null when nobody is listening (every
     * test). It is where the sequence of decisions that led to a wrong solve or a give-up actually
     * lives; the console keeps just the headline lines.
     */
    private PrintStream trace;
    /** Move counter and the tier that chose each move, for the {@link #trace}. */
    private int stepNo;
    private String tier = "";

    /**
     * Set before every move: was it the move the solved plan predicts reaches the goal? If the frame
     * right after it goes unreadable - the game closing its minigame because the lock opened - that is
     * what says "solved", where the pin-pop used to.
     */
    private boolean winningMove;

    public LockSession(LockView view, CursorKeys keys, MoveExecutor mover) {
        this.view = view;
        this.keys = keys;
        this.mover = mover;
    }

    /** Sends the verbose move-by-move trace to {@code trace} (the per-F8 log file). Off by default. */
    public void traceTo(PrintStream trace) {
        this.trace = trace;
    }

    private void trace(String line) {
        if (trace != null) {
            trace.println(line);
        }
    }

    /** The model as it now stands, for the trace: what each plate drags, and the run's counters. */
    private void traceModel() {
        if (trace == null) {
            return;
        }
        trace("model, as learned:");
        for (int p = 0; p < n; p++) {
            trace("  plate " + p + " -> " + (conn[p] == null ? "UNPROBED" : describe(conn[p])));
        }
        trace(String.format(Locale.ROOT,
                "state %s | strains %d (misread %d, gamble %d) | breaks %d | moves %d",
                Arrays.toString(cur), strains, misreadStrains, gambleStrains, observedBreaks, moves));
    }

    /**
     * A slide that strained, and the plates parked at the ends when it did. One of those plates is the
     * culprit: the move would have dragged it off its track.
     */
    private record Refusal(int plate, int dir, int plusEnds, int minusEnds) {}

    /** A move that produced a hidden row when played from the configuration {@code configKey}. */
    private record Occlusion(int plate, int dir, long configKey) {}

    /** Runs the whole routine: learn the lock, then open it. Reports; never throws. */
    public void run() {
        n = view.detectPlateCount();
        if (n < LockModel.MIN_PLATES || n > LockModel.MAX_PLATES) {
            // Not "at 4K": every resolution is supported, and saying otherwise sent one reporter
            // hunting the wrong problem for a week. When this fires with the lock plainly open on
            // screen, the frame is fine and the coordinates are not - which is what the dump says.
            System.out.println("No " + LockModel.MIN_PLATES + "-" + LockModel.MAX_PLATES + " plate "
                    + "lock detected. Is the lock open, with the game in the foreground?");
            view.dumpFrame("no-lock");
            System.out.println("If the lock was open and the saved frame looks fine, the tool is "
                    + "reading the wrong part of it: open the .txt beside it, and please report it.");
            return;
        }
        conn = new Connection[n][];
        refused.clear();
        occluded.clear();
        plan = null;
        planFrom = null;
        strains = 0;
        moves = 0;
        observedBreaks = 0;
        strainsOnThisPick = 0;
        strainsPerPick = 0;
        breakResetThePuzzle = false;
        misreadStrains = 0;
        gambleStrains = 0;
        inEscalation = false;
        corrections = 0;
        loopGuard.clear();
        lastKnowledge = Long.MIN_VALUE;
        stepNo = 0;
        tier = "";
        winningMove = false;
        // The game parks the selection on the lowest plate when a lock opens - and again whenever a
        // pick breaks. Saturating S costs n presses of ~10ms and removes the assumption entirely.
        keys.endCursor(n);

        try {
            cur = mover.settle(n);
            if (!recoverFull()) {
                unreadable("before the first move");
                return;
            }
        } catch (MoveExecutor.UnreadableFrame e) {
            unreadable("before the first move");
            return;
        }
        System.out.println("Detected " + n + " plates at " + Arrays.toString(cur)
                + ". Learning the connections, then solving.");
        trace("detected " + n + " plates at " + Arrays.toString(cur));

        try {
            loop();
        } catch (MoveExecutor.UnreadableFrame e) {
            if (winningMove) {
                // The move the plan says reaches the goal landed, and the frame after it went
                // unreadable - the minigame is closing because the lock opened.
                report("Lock solved");
            } else {
                unreadable("mid-run");
            }
        } finally {
            traceModel(); // whatever the outcome, the log ends with the model it learned
        }
    }

    private void loop() {
        while (true) {
            if (!recoverFull()) {
                System.out.println("Stuck: a plate's hole row stays hidden after every nudge. "
                        + "Slide any plate one step by hand and press F8 again.");
                view.dumpFrame("hidden-row");
                return;
            }
            if (LockSolver.isGoal(cur)) {
                // cur may be model-filled - a hidden row filled from the model - and a filled zero is a
                // guess. Confirm from a fresh DIRECT read: every plate genuinely at 0, none UNKNOWN. An
                // occluded row reads UNKNOWN here and gets nudged readable next pass; a plate that has
                // moved reads non-zero and the solver re-plans. Only an all-zero the reader actually
                // saw declares the lock open - the guarantee the pin-pop used to carry.
                int[] direct = mover.settle(n);
                if (LockModel.isComplete(direct) && LockSolver.isGoal(direct)) {
                    report("Lock solved");
                    return;
                }
                cur = direct;
                continue;
            }
            if (picksSpent() >= MAX_PICKS) {
                System.out.println("Giving up after " + strains + " strain(s), about " + picksSpent()
                        + " broken pick(s). Plates " + unprobed() + " never moved. Every lock in the "
                        + "game opens well inside this, so the fault is here, not in the lock.");
                view.dumpFrame("picks-spent");
                return;
            }
            if (misreadStrains >= MAX_MISREAD_STRAINS) {
                // A slide can only strain by dragging a plate off an end. Enough strains the read
                // called impossible means the reader has lost this frame, not that the lock is hard -
                // so stop before it spends picks, and save the frame that beat it.
                System.out.println("Stopping: " + misreadStrains + " slides I read as safe still "
                        + "strained, which can only be a misread offset. This frame is unusually dark; "
                        + "the reader has lost it, so going on would only cost picks. Saved the frame.");
                view.dumpFrame("misread");
                System.out.println("Please report the saved .png and the .txt beside it.");
                return;
            }
            if (loopingWithoutProgress()) {
                System.out.println("Stopping: I keep returning to the same configuration without "
                        + "learning anything new" + (misreadStrains > 0
                            ? " - most likely a misread on this unusually dark frame." : ".")
                        + " Saved the frame instead of looping.");
                view.dumpFrame(misreadStrains > 0 ? "misread" : "no-progress");
                return;
            }
            Move action = nextAction();
            if (action == null) {
                if (moves == 0) {
                    // Every lock the game hands you is openable, so from any configuration at least
                    // one slide is legal. If NOTHING moved - no plate, in either direction - then the
                    // lock being driven is not the lock on screen: the plate count or the offsets
                    // were misread, and every strain above was spent proving it. Reported as the bug
                    // it is, with the frame attached, instead of shrugging at the lock.
                    System.out.println("Nothing moved. No plate would slide in either direction, and "
                            + "a lock the game can open always has a legal move - so the lock I read "
                            + "is not the lock on screen (the plate count or the offsets are wrong). "
                            + "That is a bug in this tool, not a hard lock.");
                    view.dumpFrame("wrong-model");
                    System.out.println("Please report the saved .png and the .txt beside it.");
                    return;
                }
                if (allProbed()) {
                    // Every connection is known, yet the solver found no way to open it. A real lock is
                    // always openable and every move is reversible, so a fully-learned model that will
                    // not solve is a mislearned connection - a misread while probing, almost always on a
                    // dark frame. (solvingMove has already printed which configuration it gave up on.)
                    System.out.println("The connections I learned do not add up to a lock that opens, "
                            + "but the game only ever gives you locks that do - so I misread a plate "
                            + "while learning it. This frame is dark. Saved it.");
                    view.dumpFrame("unsolvable-model");
                    System.out.println("Please report the saved .png and the .txt beside it.");
                    return;
                }
                System.out.println("Stuck: no move is left to try. Plates " + unprobed()
                        + " will not budge in either direction, and no probed plate can free them."
                        + (misreadStrains > 0 ? " (" + misreadStrains + " slide(s) I read as safe still "
                            + "strained on the way - this frame is dark and I may be misreading it.)"
                            : ""));
                view.dumpFrame(misreadStrains > 0 ? "misread" : "stuck");
                return;
            }
            step(action);
        }
    }

    /**
     * The livelock guard. Every fact the run learns ({@link #knowledgeSignature} changing) empties the
     * set of configurations seen since; a configuration that comes back <b>without a new fact between</b>
     * is a loop that will never make progress - the endless "moving the same plate left and right by
     * one" a reporter watched until he alt-tabbed. Solving never trips it (a shortest path visits no
     * configuration twice), and every strain, break or probe resets it, so only a genuine no-progress
     * cycle is caught.
     */
    private boolean loopingWithoutProgress() {
        long knowledge = knowledgeSignature();
        if (knowledge != lastKnowledge) {
            loopGuard.clear();
            lastKnowledge = knowledge;
        }
        return !loopGuard.add(LockSolver.encode(model(), cur));
    }

    /**
     * A number that changes whenever the run has learned something and so made real progress: a strain,
     * a break, a row corrected, a plate probed, or a move newly ruled out as hiding a row. Undoing a
     * hidden probe lands back on the configuration it started from, which looks like a loop but is not -
     * an occlusion was recorded, and next time a different move is tried. Counting {@code occluded} keeps
     * that honest progress from tripping {@link #loopingWithoutProgress}.
     */
    private long knowledgeSignature() {
        long probed = 0;
        for (int p = 0; p < n; p++) {
            if (conn[p] != null) probed |= 1L << p;
        }
        return (((long) strains) << 44) ^ (((long) observedBreaks) << 34)
                ^ (((long) occluded.size()) << 24) ^ (((long) corrections) << 16) ^ (probed << 8);
    }

    /** Plays one move and folds what happened back into what we know. */
    private void step(Move move) {
        int p = move.plate();
        int dir = move.dir();
        int[] before = cur;
        // Will this move, if it lands, open the lock? Meaningful only once the model is complete, so a
        // probe that goes unreadable is never mistaken for the winning move by the catch in run().
        int[] predicted = allProbed() ? LockSolver.applyMove(model(), before, p, dir) : null;
        winningMove = predicted != null && LockSolver.isGoal(predicted);
        MoveExecutor.Observation obs = mover.play(n, before, move);
        trace(String.format(Locale.ROOT, "step %d [%s] plate %d %s: %s -> %s (%s%s)",
                ++stepNo, tier, p, dir > 0 ? "left" : "right", Arrays.toString(before),
                Arrays.toString(obs.state()), obs.outcome(), obs.pickBroke() ? ", pick broke" : ""));

        if (obs.outcome() == MoveExecutor.Outcome.MOVED && !obs.pickBroke()) {
            moves++;
            if (LockModel.isComplete(obs.state())) {
                cur = obs.state();
                learn(p, before, cur);
                advancePlan(move);
            } else {
                plan = null;
                partiallyObserved(move, before, obs.state());
            }
            return;
        }

        // Everything else is a strain: the lock did not do what we asked.
        plan = null; // the lock is not where the plan thought; plan again from what is really there
        int plusEnds = ends(before, +1, p);
        int minusEnds = ends(before, -1, p);
        // A slide can only strain by dragging some OTHER plate off an end. So a strain with nothing at
        // either end is physically impossible on a lock we read correctly - the geometry that made this
        // move look safe was itself a misread. Recording it as a refusal would be worse than useless:
        // an empty culprit set never expires ({@link #isRefused}), permanently wedging the plate, which
        // - together with an unblock plan that keeps "freeing" an already-free plate - is the endless
        // left-right loop. So a contradictory strain is booked as a misread, never a refusal.
        boolean contradictory = plusEnds == 0 && minusEnds == 0;
        strained();
        if (inEscalation) {
            gambleStrains++; // a gamble a reposition set up, and the whole point of the sub-budget
        }
        if (contradictory) {
            if (!obs.pickBroke()) {
                misreadStrains++;
            }
        } else {
            refused.add(new Refusal(p, dir, plusEnds, minusEnds));
        }
        cur = obs.state();
        // The game may have moved the selection - it re-homes it whenever a pick breaks, and a break
        // is invisible above skill level 0. Saturating S is right either way, at ~10ms a press.
        keys.endCursor(n);

        if (obs.pickBroke()) {
            recordBreak(obs.outcome() == MoveExecutor.Outcome.RESET);
            System.out.println("  the pick broke after " + strainsPerPick + " strain(s)" + levelSeen()
                    + " (" + observedBreaks + " so far)"
                    + (obs.outcome() == MoveExecutor.Outcome.RESET
                        ? "; the puzzle reset to " + Arrays.toString(cur)
                        : "; the lock is untouched at " + Arrays.toString(cur))
                    + ". Keeping every connection learned so far.");
            return;
        }
        if (conn[p] != null) {
            // The model swore this was legal. It is the model that is wrong, so re-probe p.
            System.out.println("  plate " + p + " refused a move its connections say is legal;"
                    + " discarding what we knew about it.");
            conn[p] = null;
            corrections++;
        } else if (contradictory) {
            // Nothing was at an end, so don't retry this exact slide from this exact configuration;
            // once some plate moves, the misread may resolve and it becomes worth another look.
            occluded.add(new Occlusion(p, dir, LockSolver.encode(model(), before)));
            System.out.println("  plate " + p + " would not slide " + name(dir) + " though nothing sits "
                    + "at an end - I am misreading a plate on this dark frame (" + misreadStrains
                    + " so far).");
        } else {
            System.out.println("  plate " + p + " will not slide " + name(dir) + " yet: it must drag a "
                    + "plate already at the end of its track (strain " + strains + ").");
        }
    }

    /** A successful move reveals exactly what {@code p} drags - which also repairs a wrong row. */
    private void learn(int p, int[] before, int[] after) {
        List<Connection> row = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            if (q == p || after[q] == before[q]) continue;
            Connection.Type type =
                    Integer.signum(after[q] - before[q]) == Integer.signum(after[p] - before[p])
                            ? Connection.Type.NORMAL : Connection.Type.INVERTED;
            row.add(new Connection(q, type));
        }
        boolean first = conn[p] == null;
        Connection[] learned = row.toArray(new Connection[0]);
        if (!first && !Arrays.equals(conn[p], learned)) {
            plan = null; // the model the plan was built on was wrong about this plate
            corrections++; // a fact changed - progress, for the loop guard
        }
        conn[p] = learned;
        if (first) {
            inEscalation = false; // a plate newly probed is real progress; leave the escalation regime
            System.out.println("  plate " + p + " drags " + describe(conn[p])
                    + (allProbed() ? "  -- every connection known, solving" : ""));
        }
    }

    // --- hidden rows ---

    /**
     * The move landed, but it produced a configuration in which some plate's row is hidden. A diff
     * with a hidden plate could teach a silently wrong row, so nothing is learned from it: while
     * solving, the gap is filled from the model when the model explains every visible plate;
     * otherwise the move is undone and remembered as occluding from that configuration.
     */
    private void partiallyObserved(Move move, int[] before, int[] seen) {
        int p = move.plate();
        if (allProbed()) {
            int[] predicted = LockSolver.applyMove(model(), before, p, move.dir());
            if (predicted != null && matchesReadable(predicted, seen)) {
                // The model explains everything visible, so the hidden plates followed it too.
                // Every further move keeps re-verifying, and only a directly-observed all-zero
                // declares the goal.
                System.out.println("  a hole row is hidden; filling it in from the model.");
                cur = predicted;
                return;
            }
            System.out.println("  plate " + p + " did not do what its connections predicted, and a"
                    + " hidden row prevents relearning here; discarding what we knew about it.");
            conn[p] = null;
        } else {
            System.out.println("  plate " + p + "'s move hid another plate's row; undoing it to"
                    + " probe again from a different configuration.");
        }
        occluded.add(new Occlusion(p, move.dir(), LockSolver.encode(model(), before)));
        MoveExecutor.Observation undo = mover.play(n, seen, new Move(p, -move.dir()));
        absorb(undo);
        cur = undo.state(); // possibly still hidden; the loop's recovery pass sorts it out
    }

    /**
     * Makes {@code cur} fully observed again, first by re-settling (a transient cover - the mouse
     * cursor, a tooltip - resolves by itself), then by nudging visible interior plates one step to
     * change the geometry that hides the row. A nudge can strain (it may drag a hidden plate parked
     * at an end), which is accepted: there is no way to prove anything about a lock that cannot be
     * fully seen.
     */
    private boolean recoverFull() {
        if (LockModel.isComplete(cur)) return true;
        cur = mover.settle(n);
        Set<Integer> tried = new HashSet<>();
        for (int nudges = 0; nudges < MAX_NUDGES && !LockModel.isComplete(cur); nudges++) {
            Move nudge = unoccludeNudge(tried);
            if (nudge == null) return false;
            tried.add(nudge.plate() * 4 + (nudge.dir() > 0 ? 1 : 0));
            System.out.println("  a hole row is hidden; nudging plate " + nudge.plate()
                    + " to uncover it.");
            MoveExecutor.Observation obs = mover.play(n, cur, nudge);
            absorb(obs);
            cur = obs.state();
        }
        return LockModel.isComplete(cur);
    }

    /** A visible, interior plate to move one step, preferring toward centre; null if none left. */
    private Move unoccludeNudge(Set<Integer> tried) {
        for (int p = n - 1; p >= 0; p--) {
            if (cur[p] == LockModel.UNKNOWN) continue;
            int toward = cur[p] > 0 ? -1 : +1;
            for (int dir : new int[] {toward, -toward}) {
                if (Math.abs(cur[p] + dir) > LockModel.MAX_OFFSET) continue;
                if (tried.contains(p * 4 + (dir > 0 ? 1 : 0))) continue;
                return new Move(p, dir);
            }
        }
        return null;
    }

    /** True if every visible entry of {@code seen} equals {@code predicted}'s. */
    private static boolean matchesReadable(int[] predicted, int[] seen) {
        for (int i = 0; i < seen.length; i++) {
            if (seen[i] != LockModel.UNKNOWN && seen[i] != predicted[i]) return false;
        }
        return true;
    }

    // --- choosing the next move ---

    /** The next move to play: a discovery move while anything is unprobed, otherwise a solving move. */
    private Move nextAction() {
        if (allProbed()) {
            inEscalation = false;
            tier = "solving";
            return solvingMove();
        }
        Move free = freeMove();
        if (free != null) {
            inEscalation = false;
            tier = "free";
            return free;
        }
        Move planned = plannedMove();
        if (planned != null) {
            inEscalation = false;
            tier = "planned";
            return planned;
        }
        Move gamble = gamble();
        // A non-null gamble may be the very one a reposition just set up, so its strain must still be
        // charged to the escalation budget - leave inEscalation as it stands.
        if (gamble != null) {
            tier = inEscalation ? "reposition-gamble" : "gamble";
            return gamble;
        }
        // Last resort, and the reason "stuck" is now rarer: shuffle plates we already understand until
        // an unprobed one can be gambled in a direction we have not already ruled out, then let the
        // gamble fire. It is the only thing that opens a lock where probing one plate strands another
        // at an end. Capped by MAX_GAMBLE_STRAINS so a hard lock can never eat the inventory.
        if (gambleStrains >= MAX_GAMBLE_STRAINS) {
            inEscalation = false;
            return null;
        }
        Move setup = escalate();
        inEscalation = setup != null;
        return setup;
    }

    /**
     * The next move of the solution, re-solving only when the plan in hand no longer applies.
     *
     * <p>A search costs real time - ~430ms on a six-plate lock, against the ~320ms it takes to
     * watch a slide animate - so re-solving before every move once cost more than the game did
     * (measured: 9.1s of a 22.1s run, spent computing the same 22-move plan 22 times over). The
     * plan is still <i>verified</i> every move rather than trusted: {@link #advancePlan} keeps it
     * only while the lock arrives in exactly the configuration the plan's head expects, and any
     * surprise - a strain, a hidden row, a connection row that turned out different - drops it and
     * pays for a fresh search. So a surprise still costs one move, not the run.
     */
    private Move solvingMove() {
        if (plan != null && !plan.isEmpty() && Arrays.equals(planFrom, cur)) {
            return plan.get(0);
        }
        List<Move> moves = LockSolver.solve(model(), cur, keys.cursor(), Cost.WALLCLOCK);
        if (moves == null || moves.isEmpty()) {
            plan = null;
            System.out.println("No strain-free solution exists from " + Arrays.toString(cur) + ".");
            return null;
        }
        plan = new ArrayList<>(moves);
        planFrom = cur.clone();
        trace("solve from " + Arrays.toString(cur) + ": " + moves.size() + " moves " + planMoves(moves));
        return plan.get(0);
    }

    /**
     * Keeps the plan only if the move just played was its head and the lock landed where the plan
     * said it would; otherwise throws it away, so the next move is planned from what is really
     * there.
     */
    private void advancePlan(Move played) {
        if (plan != null && !plan.isEmpty() && plan.get(0).equals(played)) {
            plan.remove(0);
            planFrom = cur.clone();
        } else {
            plan = null;
        }
    }

    /** A plate every other plate has stepped clear of: sliding it cannot strain. */
    private Move freeMove() {
        for (int p = n - 1; p >= 0; p--) { // the selection starts low, so probing upward is cheapest
            if (conn[p] != null || othersAtEnds(cur, p) != 0) continue;
            int dir = pickDirection(p);
            if (dir != 0) return new Move(p, dir);
        }
        return null;
    }

    /**
     * The first move of a plan that frees some unprobed plate, using only plates whose rows we know -
     * so {@link LockSolver#applyMove} proves every move of it legal before a key is pressed. Costs
     * time, never a pick, which is exactly the trade this routine is supposed to make.
     */
    private Move plannedMove() {
        for (int p = 0; p < n; p++) {
            if (conn[p] != null) continue;
            List<Move> plan = planUnblock(p);
            if (plan != null && !plan.isEmpty()) {
                System.out.println("  plate " + p + " is blocked; clearing the ends for it with "
                        + plan.size() + " move(s) of plates we already understand.");
                return plan.get(0);
            }
        }
        return null;
    }

    /**
     * Press a key and accept the risk. Plates with the fewest others parked at ends go first, because
     * those are the likeliest to move; and each slides toward centre, which walks it off an end and
     * makes every later move safer.
     */
    private Move gamble() {
        List<Integer> order = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            if (conn[p] == null) order.add(p);
        }
        order.sort(Comparator.comparingInt(p -> othersAtEnds(cur, p)));
        for (int p : order) {
            int dir = pickDirection(p);
            if (dir != 0) return new Move(p, dir);
        }
        return null;
    }

    /** Toward centre if that is still worth trying, else away from it, else 0, from {@code cur}. */
    private int pickDirection(int p) {
        return pickDirectionAt(cur, p);
    }

    /**
     * A direction plate {@code p} is worth trying from {@code state} - toward centre first, then away -
     * skipping any that would slide {@code p} off its track, is already known to strain, or is known to
     * hide a row from here; 0 if none is left. State-parameterized so the reposition search can ask it
     * of a configuration the lock is not in yet.
     */
    private int pickDirectionAt(int[] state, int p) {
        int toward = state[p] > 0 ? -1 : +1;
        for (int dir : new int[] {toward, -toward}) {
            if (Math.abs(state[p] + dir) > LockModel.MAX_OFFSET) continue; // p itself would fall off
            if (isRefused(state, p, dir)) continue;
            if (isOccludedAt(state, p, dir)) continue;
            return dir;
        }
        return 0;
    }

    /** True if this exact move, from this exact configuration, is known to hide a row. */
    private boolean isOccludedAt(int[] state, int p, int dir) {
        long key = LockSolver.encode(model(), state);
        for (Occlusion o : occluded) {
            if (o.plate() == p && o.dir() == dir && o.configKey() == key) return true;
        }
        return false;
    }

    /**
     * Breadth-first search for the shortest sequence of moves of already-probed plates after which
     * every plate except {@code target} is off the ends of its track - the condition that makes
     * {@code target} guaranteed to slide. Returns null if no such state is reachable.
     */
    private List<Move> planUnblock(int target) {
        // The ends are already clear for the target: there is nothing to plan. Left unguarded, the BFS
        // below still returns a one-move detour to a neighbouring also-clear state, and if the target
        // is nonetheless unplayable (both directions refused), plannedMove keeps ordering that detour
        // forever - the endless one-step oscillation a reporter had to alt-tab out of.
        if (othersAtEnds(cur, target) == 0) return null;
        LockModel m = model();
        List<Integer> movers = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            if (conn[q] != null) movers.add(q); // unprobed: we cannot prove its move is legal
        }
        if (movers.isEmpty()) return null;

        Map<Long, long[]> cameFrom = new HashMap<>(); // stateKey -> {prevKey, plate, dir}
        Map<Long, int[]> seen = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        long startKey = LockSolver.encode(m, cur);
        seen.put(startKey, cur);
        queue.add(cur);

        while (!queue.isEmpty() && seen.size() < MAX_SEARCH_STATES) {
            int[] state = queue.poll();
            long key = LockSolver.encode(m, state);
            for (int q : movers) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int[] next = LockSolver.applyMove(m, state, q, dir);
                    if (next == null) continue; // would strain
                    long nextKey = LockSolver.encode(m, next);
                    if (seen.putIfAbsent(nextKey, next) != null) continue;
                    cameFrom.put(nextKey, new long[] {key, q, dir});
                    if (othersAtEnds(next, target) == 0) return path(cameFrom, startKey, nextKey);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    private static List<Move> path(Map<Long, long[]> cameFrom, long startKey, long endKey) {
        List<Move> moves = new ArrayList<>();
        for (long key = endKey; key != startKey; ) {
            long[] step = cameFrom.get(key);
            moves.add(new Move((int) step[1], (int) step[2]));
            key = step[0];
        }
        Collections.reverse(moves);
        return moves;
    }

    /**
     * The first move of a reposition that ends where an unprobed plate can finally be gambled - the
     * last-resort step {@link #nextAction} takes when nothing safe is left. Its point is the case that
     * used to report "stuck: no move left to try" on a solvable lock: probing one interior plate drags
     * another to an end, where its only informative direction becomes off-track, and discovery
     * dead-ends. Walking the plates we already understand back frees the stranded one, and then the
     * ordinary gamble reveals its row and breaks the deadlock. A successful gamble costs no strain; a
     * failed one is charged to {@link #MAX_GAMBLE_STRAINS}.
     */
    private Move escalate() {
        List<Move> setup = repositionForFreshGamble();
        if (setup == null || setup.isEmpty()) {
            return null;
        }
        System.out.println("  no safe probe left; repositioning plates we understand to free one to "
                + "gamble (" + setup.size() + " move(s)).");
        return setup.get(0);
    }

    /**
     * Breadth-first search, over moves of already-probed plates only (each proven legal by
     * {@link LockSolver#applyMove}), for the shortest way to a configuration in which some still-unprobed
     * plate can be gambled in a direction not already ruled out ({@link #hasFreshGamble}). Returns null
     * when no such configuration is reachable - which is exactly a genuinely deadlocked lock, so the
     * caller then stops instead of retrying, and the two-strain budget of a real deadlock is preserved.
     */
    private List<Move> repositionForFreshGamble() {
        LockModel m = model();
        List<Integer> movers = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            if (conn[q] != null) movers.add(q);
        }
        if (movers.isEmpty()) return null;

        Map<Long, long[]> cameFrom = new HashMap<>();
        Map<Long, int[]> seen = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        long startKey = LockSolver.encode(m, cur);
        seen.put(startKey, cur);
        queue.add(cur);

        while (!queue.isEmpty() && seen.size() < MAX_SEARCH_STATES) {
            int[] state = queue.poll();
            long key = LockSolver.encode(m, state);
            for (int q : movers) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int[] next = LockSolver.applyMove(m, state, q, dir);
                    if (next == null) continue; // would strain
                    long nextKey = LockSolver.encode(m, next);
                    if (seen.putIfAbsent(nextKey, next) != null) continue;
                    cameFrom.put(nextKey, new long[] {key, q, dir});
                    if (hasFreshGamble(next)) return path(cameFrom, startKey, nextKey);
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** True if some unprobed plate can be gambled from {@code state} in a not-yet-ruled-out direction. */
    private boolean hasFreshGamble(int[] state) {
        for (int q = 0; q < n; q++) {
            if (conn[q] == null && pickDirectionAt(state, q) != 0) return true;
        }
        return false;
    }

    // --- the geometry of a block ---

    /**
     * How many plates OTHER than {@code p} sit at an end of their track. Zero means a move of {@code p}
     * cannot strain: anything it drags is interior, and a drag is only ever one step.
     */
    private static int othersAtEnds(int[] state, int p) {
        return Integer.bitCount(ends(state, +1, p)) + Integer.bitCount(ends(state, -1, p));
    }

    /** Bit set of the plates, other than {@code exclude}, parked at the {@code side} end. */
    private static int ends(int[] state, int side, int exclude) {
        int bits = 0;
        for (int i = 0; i < state.length; i++) {
            if (i != exclude && state[i] == side * LockModel.MAX_OFFSET) bits |= 1 << i;
        }
        return bits;
    }

    /**
     * True if we already know this slide strains. It failed because some plate other than {@code p} sat
     * at an end and would have been dragged off it. While <i>every</i> plate that was at an end back
     * then is still at that same end, the culprit is still among them and the move still fails - so
     * retrying would only feed the strain counter. As soon as one of them steps away, the refusal
     * expires and the move is worth another try.
     *
     * <p>This is what makes a broken pick survivable: the reset restores the very configuration the
     * gamble failed in, and the refusal recorded there still applies.
     */
    private boolean isRefused(int[] state, int p, int dir) {
        int plus = ends(state, +1, p);
        int minus = ends(state, -1, p);
        for (Refusal r : refused) {
            if (r.plate() != p || r.dir() != dir) continue;
            if ((r.plusEnds() & ~plus) == 0 && (r.minusEnds() & ~minus) == 0) return true;
        }
        return false;
    }

    /**
     * Books the outcome of a move played outside the main loop - an undo, a nudge - where there is
     * nothing to learn and nothing to refuse, only a strain or a break to count and a selection to
     * re-home (the game moves it whenever a pick breaks).
     */
    private void absorb(MoveExecutor.Observation obs) {
        if (obs.pickBroke()) {
            strained(); // a pick only ever breaks on a strain
            recordBreak(obs.outcome() == MoveExecutor.Outcome.RESET);
            keys.endCursor(n);
        } else if (obs.outcome() == MoveExecutor.Outcome.UNCHANGED) {
            strained();
            keys.endCursor(n);
        }
    }

    private void strained() {
        strains++;
        strainsOnThisPick++;
    }

    /**
     * Books a broken pick, and with it the one thing the run learns about the player: the strains
     * that pick survived are this character's strains-per-pick, and a break that also slid every
     * plate home can only have happened untrained. Remembered for the report, and no longer - the
     * character may train lockpicking before the next lock.
     */
    private void recordBreak(boolean puzzleReset) {
        observedBreaks++;
        strainsPerPick = strainsOnThisPick;
        strainsOnThisPick = 0;
        breakResetThePuzzle |= puzzleReset;
    }

    // --- helpers ---

    /** A model over what we know; unprobed plates are treated as dragging nothing. */
    private LockModel model() {
        Connection[][] known = new Connection[n][];
        for (int i = 0; i < n; i++) {
            known[i] = conn[i] != null ? conn[i] : new Connection[0];
        }
        return new LockModel(n, cur, known, LockModel.MAX_OFFSET);
    }

    private boolean allProbed() {
        for (Connection[] row : conn) {
            if (row == null) return false;
        }
        return true;
    }

    private List<Integer> unprobed() {
        List<Integer> out = new ArrayList<>();
        for (int p = 0; p < n; p++) {
            if (conn[p] == null) out.add(p);
        }
        return out;
    }

    /**
     * Picks spent - counted, not estimated. The remaining-lockpicks counter changes at every skill
     * level, so {@code Slider} sees every break, and there is nothing here to infer.
     */
    private int picksSpent() {
        return observedBreaks;
    }

    private void report(String what) {
        System.out.println(what + ". " + strains + " strain(s), " + picksSpent()
                + " pick(s) broken." + skillSeen());
    }

    /** The tail of the report line, when a pick broke and so said something about the character. */
    private String skillSeen() {
        if (observedBreaks == 0) {
            return "";
        }
        return " The last pick took " + strainsPerPick + " strain(s)" + levelSeen() + ".";
    }

    /**
     * The lockpicking level the last broken pick revealed, as a clause to append - or nothing.
     *
     * <p>Nothing depends on this; it is for the player to read. And it is deliberately hedged: a
     * pick carries its damage between locks, so the first one a run breaks may have arrived already
     * worn and break early. A strain count that matches no level is reported as exactly that rather
     * than rounded into a guess. The one thing that is certain either way is the reset: only an
     * untrained character's break sends every plate home.
     */
    private String levelSeen() {
        return Skill.fromStrainsPerPick(strainsPerPick)
                .map(s -> " - that is " + s.name().toLowerCase() + " lockpicking")
                .orElse(breakResetThePuzzle
                        ? " - the puzzle reset, so the character is untrained"
                        : " - no level breaks on that many, so that pick came in already worn");
    }

    private void unreadable(String when) {
        System.out.println("Could not read the lock " + when + ". Is something covering it (a tooltip, "
                + "the mouse cursor, a notification), or did the game move on?");
        view.dumpFrame("unreadable");
    }

    private static String name(int dir) {
        return dir > 0 ? "left" : "right";
    }

    /** A plan as a compact list of {@code <plate><L|R>} moves, for the trace. */
    private static String planMoves(List<Move> moves) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < moves.size(); i++) {
            Move m = moves.get(i);
            sb.append(i > 0 ? " " : "").append(m.plate()).append(m.dir() > 0 ? "L" : "R");
        }
        return sb.append(']').toString();
    }

    private static String describe(Connection[] row) {
        if (row.length == 0) return "nothing";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < row.length; j++) {
            if (j > 0) sb.append(", ");
            sb.append("plate ").append(row[j].target())
              .append(row[j].type() == Connection.Type.NORMAL ? " (normal)" : " (inverted)");
        }
        return sb.toString();
    }
}
