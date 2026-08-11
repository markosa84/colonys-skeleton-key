package io.github.markosa84.colonysskeletonkey.session;

import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.ConnectionPrior;
import io.github.markosa84.colonysskeletonkey.solver.Cost;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.ModelRepair;
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
 * <h2>Locks already opened</h2>
 * None of that is needed for a chest this tool has met before. The offsets a lock shows at F8 nearly
 * always identify it - measured across 186 real chests, with one collision - so {@link KnownLocks}
 * is consulted first and, on a hit, the whole model is adopted and the run goes straight to solving.
 * It is a hypothesis, not an assumption: it is taken up only if it opens the lock from where the lock
 * actually is, and every move below still verifies it. See {@link #adopt}.
 *
 * <p><b>Nearly</b>, because the key is a measurement rather than a theorem, and two chests do share a
 * starting configuration now and then - a four-plate lock has only 7^4 of them, and the ends of the
 * track are twice as likely as anywhere else. So the run also watches for the one contradiction the
 * skill mechanic cannot produce, and when it comes it throws the <i>whole</i> memory away rather than
 * the row it was caught on. See {@link #discardRecall}, which is the fix for a real chest the tool
 * blamed its own reader for.
 *
 * <h2>Picks first, time second</h2>
 * Each move is chosen cheapest-risk-first:
 * <ol>
 *   <li><b>Free</b> - every other plate is already interior, so sliding this one cannot strain.</li>
 *   <li><b>Planned</b> - breadth-first search for moves of <i>already-probed</i> plates that clear the
 *       ends for some unprobed plate. Their rows are known, so {@link LockSolver#applyMove} proves each
 *       move legal before a key is pressed. Costs time, never a pick.</li>
 *   <li><b>Gamble</b> - when neither exists, and then the cheapest gamble on the board:
 *       {@link io.github.markosa84.colonysskeletonkey.solver.ConnectionPrior} costs every unprobed
 *       plate against both directions from what 186 real locks say about how a lock is wired. Toward
 *       centre survives as the tie-break, since it still walks plates off the ends.</li>
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
 * <p>And a strain is made to pay for itself. It proves the plate dragged <i>something</i> off an end,
 * so the culprit is among the plates parked out there, dragged the one way that would push it off;
 * when only one plate qualifies - a third of real strains - that connection is settled outright
 * ({@link #deduceFrom}). What it buys is {@link #certainlyStrains}: a move ruled out from <b>any</b>
 * configuration, where the refusal memory only recognises the same slide from a configuration where
 * every plate that was at an end still is.
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
    /**
     * Suspect re-probes a stuck-unsolvable run may spend before it gives up. A misread while probing
     * corrupts one plate's connection row and makes the whole model unopenable; because the game only
     * ever hands out solvable locks, a fully-probed model that will not solve is proof of exactly that,
     * so the run re-probes the likeliest culprit rather than shrugging. Bounded so a genuinely
     * multiply-corrupted read still stops and dumps its frame instead of churning.
     */
    private static final int MAX_RECOVERY_RESETS = 4;
    /**
     * Broken picks the unsolvable-model recovery may cost - well under {@link #MAX_PICKS}, so rescuing
     * a misread can never eat the inventory. These dark-frame locks are usually untrained, where every
     * second strain breaks a pick and a break resets the puzzle, so the recovery must stay cheap.
     */
    private static final int MAX_RECOVERY_PICKS = 2;

    private final LockView view;
    private final CursorKeys keys;
    private final MoveExecutor mover;
    private final SessionReporter reporter;
    /** Locks opened before, consulted instead of probing when this one is among them. */
    private final KnownLocks known;

    private int n;
    /** {@code conn[p]} is what moving p drags, or null while p is unprobed. */
    private Connection[][] conn;
    /**
     * {@code fromRecall[p]} while {@code conn[p]} came out of {@link KnownLocks} rather than off the
     * screen. Such a row is used exactly like a probed one - the plan it feeds is verified move by
     * move - but it is the first thing recovery suspects, and the first thing an observation replaces.
     */
    private boolean[] fromRecall;
    /**
     * True once a remembered model has been adopted, so the search for one stops - and it stays true
     * after {@link #discardRecall} throws that model away, which is what spends recall for the rest
     * of the run. The alternative, letting {@link #recogniseFromProbedRows} look again, sounds
     * strictly better and is not: it asks a weaker question (does a model agree with the rows probed
     * so far?), and the chest that just fooled us can answer yes on every row it was not caught on.
     * Re-adopting it would cost another contradiction to shake off, and each of those counts as
     * progress, so the loop guard would not catch the cycle. Probing always works; this run probes.
     */
    private boolean recallAdopted;
    /**
     * True once anything was ever recalled this run, even if it has since been discarded. It is what
     * keeps the {@code moves == 0} give-up honest: that message blames the <b>reader</b>, and it may
     * only do so when every row it drove came off the screen.
     */
    private boolean everRecalled;
    /**
     * Connections proved by a strain rather than seen: a <b>partial</b> row, never a complete one.
     * A slide can only strain by dragging a plate off an end, so when exactly one plate sits where
     * this slide could have pushed it off, that plate and the way it is dragged are settled - no
     * probability involved. Measured over the corpus, a third of real strains are that clear-cut.
     *
     * <p>Kept apart from {@link #conn} on purpose. {@code conn[p] != null} means "this row is
     * complete", which is what licenses {@link LockSolver#applyMove} to call a move legal. A partial
     * row can only ever prove the opposite - that a move <i>will</i> strain - so it is used for that,
     * for weighing a gamble, and for nothing else.
     */
    private Connection[][] deduced;
    /** Deductions made this run; a progress signal for the loop guard. */
    private int deductions;
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
     * Plates whose learned row was ever contradicted by a later observation - the first suspects when a
     * fully-probed model still will not open. A misread that only shows up in one configuration reads
     * differently once, and that disagreement is the loudest clue to which plate was misread.
     */
    private boolean[] contested;
    /** Clean observations folded into each plate's row so far; a low count is a weakly-confirmed row. */
    private int[] observationCount;
    /**
     * Suspect plates already re-probed by the unsolvable-model recovery without their row changing.
     * Skipped when choosing the next suspect, so recovery moves on instead of re-clearing the same
     * plate; cleared the moment any re-probe actually changes a row (real progress - see {@link #learn}).
     */
    private final Set<Integer> recoveryTried = new HashSet<>();
    /** The row a recovery reset cleared, kept so a re-probe returning a DIFFERENT row counts as progress. */
    private final Map<Integer, Connection[]> preRecoveryRow = new HashMap<>();
    /** Suspect re-probes spent this run, capped at {@link #MAX_RECOVERY_RESETS}. */
    private int recoveryResets;
    /** Broken-pick count when recovery first fired, to bound its spend to {@link #MAX_RECOVERY_PICKS}. */
    private int breaksWhenRecoveryBegan;
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

    /**
     * The lock as it stood when F8 was pressed - snapshotted at the first read, because {@link #cur}
     * is overwritten as the run proceeds. Exposed by {@link #initialState()} for the solve history.
     */
    private int[] initialState;
    /** Set the moment the run concludes the lock is open; read back by {@link #solved()}. */
    private boolean solved;

    public LockSession(LockView view, CursorKeys keys, MoveExecutor mover) {
        this(view, keys, mover, KnownLocks.NONE);
    }

    /** Consulting what has already been opened, which is how a known lock skips discovery entirely. */
    public LockSession(LockView view, CursorKeys keys, MoveExecutor mover, KnownLocks known) {
        this(view, keys, mover, known, new SessionReporter());
    }

    /** With an injected reporter, so a test can watch the console story without scraping stdout. */
    LockSession(LockView view, CursorKeys keys, MoveExecutor mover, SessionReporter reporter) {
        this(view, keys, mover, KnownLocks.NONE, reporter);
    }

    LockSession(LockView view, CursorKeys keys, MoveExecutor mover, KnownLocks known,
            SessionReporter reporter) {
        this.view = view;
        this.keys = keys;
        this.mover = mover;
        this.known = known;
        this.reporter = reporter;
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
            trace("  plate " + p + " -> " + (conn[p] == null
                    ? "UNPROBED"
                    : SessionReporter.describe(conn[p])
                            + (fromRecall[p] ? "   (remembered, not yet re-observed)" : "")));
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
            reporter.noLockDetected();
            view.dumpFrame("no-lock");
            return;
        }
        conn = new Connection[n][];
        contested = new boolean[n];
        observationCount = new int[n];
        fromRecall = new boolean[n];
        recallAdopted = false;
        everRecalled = false;
        deduced = new Connection[n][];
        Arrays.fill(deduced, new Connection[0]);
        deductions = 0;
        recoveryTried.clear();
        preRecoveryRow.clear();
        recoveryResets = 0;
        breaksWhenRecoveryBegan = -1;
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
        solved = false;
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
        initialState = cur.clone(); // cur is overwritten as we go; the history wants the F8-time state
        reporter.detected(n, cur);
        trace("detected " + n + " plates at " + Arrays.toString(cur));
        // The offsets a lock shows at F8 identify it, so this is the whole of discovery on a chest
        // that has been opened before. Nothing is trusted by adopting it - every move below is still
        // checked against what the lock actually does.
        known.recall(n, cur).ifPresent(model -> adopt(model, "I have opened this lock before"));

        try {
            loop();
        } catch (MoveExecutor.UnreadableFrame e) {
            if (winningMove) {
                // The move the plan says reaches the goal landed, and the frame after it went
                // unreadable - the minigame is closing because the lock opened.
                reportSolved();
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
                reporter.stuckHiddenRow();
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
                    reportSolved();
                    return;
                }
                cur = direct;
                continue;
            }
            if (picksSpent() >= MAX_PICKS) {
                reporter.giveUpPicksSpent(strains, picksSpent(), unprobed());
                view.dumpFrame("picks-spent");
                return;
            }
            if (misreadStrains >= MAX_MISREAD_STRAINS) {
                // A slide can only strain by dragging a plate off an end. Enough strains the read
                // called impossible means the reader has lost this frame, not that the lock is hard -
                // so stop before it spends picks, and save the frame that beat it.
                reporter.misreadGiveUp(misreadStrains);
                view.dumpFrame("misread");
                return;
            }
            if (loopingWithoutProgress()) {
                reporter.noProgress(misreadStrains);
                view.dumpFrame(misreadStrains > 0 ? "misread" : "no-progress");
                return;
            }
            Move action = nextAction();
            if (action == null) {
                if (moves == 0) {
                    // Every lock the game hands you is openable, so from any configuration at least
                    // one slide is legal. If NOTHING moved - no plate, in either direction - then the
                    // lock being driven is not the lock on screen.
                    //
                    // WHICH lock, though, depends on where the model came from, and only one of the
                    // two answers is a reader bug. A run that recalled a model may simply have been
                    // handed another chest's: the offsets key the catalogue, and they collide. Saying
                    // "the plate count or the offsets are wrong" there sends the player hunting a
                    // vision bug over a frame that read perfectly - which is exactly what happened.
                    if (everRecalled) {
                        reporter.falseRecallGiveUp();
                        view.dumpFrame("false-recall");
                        return;
                    }
                    // Nothing was ever recalled, so every row driving this came off the screen: the
                    // plate count or the offsets really are wrong, and every strain above was spent
                    // proving it. Reported as the bug it is, with the frame attached.
                    reporter.wrongModel();
                    view.dumpFrame("wrong-model");
                    return;
                }
                if (allProbed()) {
                    // Every connection is known, yet the solver found no way to open it. A real lock is
                    // always openable and every move is reversible, so a fully-learned model that will
                    // not solve is a mislearned connection - a misread while probing, almost always on a
                    // dark frame. Rather than give up, re-probe the likeliest misread plate: recovery
                    // clears it and lets discovery relearn it from the configuration the lock is now in.
                    if (tryRecoverUnsolvable()) {
                        continue;
                    }
                    reporter.unsolvableModelGiveUp();
                    view.dumpFrame("unsolvable-model");
                    return;
                }
                reporter.stuck(unprobed(), misreadStrains);
                view.dumpFrame(misreadStrains > 0 ? "misread" : "stuck");
                return;
            }
            int probedBefore = probedCount();
            step(action);
            if (probedCount() > probedBefore) {
                recogniseFromProbedRows();
            }
        }
    }

    // --- locks opened before ---

    /**
     * Takes a remembered model as the starting point instead of probing for one, but only if it opens
     * the lock from where the lock actually is - a model that cannot is stale, or belongs to a
     * different chest, and is dropped without a key being pressed.
     *
     * <p>Rows this run has observed for itself are never overwritten: an observation outranks a
     * memory. What is adopted is only the gap, and each adopted row is flagged {@link #fromRecall} so
     * a later observation replaces it and {@link #suspectPlate} suspects it first.
     *
     * <p>This is safe rather than merely convenient <b>as far as the character's skill goes</b>, and
     * the reason is the game's own skill mechanic. Trained removes one plate connection and Master
     * removes another, so a lock is a <b>subset</b> of its untrained self, and
     * {@link LockSolver#applyMove} can only fail by pushing an affected plate off its track - a subset
     * affects fewer plates, by the same deltas. A remembered model that is too <i>full</i> therefore
     * mispredicts where the plates land, and the verification catches that in one move; it cannot
     * strain the pick.
     *
     * <p><b>Identity is the other half, and it is not free.</b> That argument says a memory of
     * <i>this chest</i> is safe; it says nothing about whether this is that chest. The key is
     * {@code (plate count, offsets)} - one collision in 186 real locks, and a property
     * of the game rather than a theorem, and with the smallest key space (four plates, 7^4 states
     * skewed hard toward the ends of the track) it does collide. A memory of a <i>different</i> chest
     * is neither superset nor subset, and it can strain. So the run watches for exactly the
     * contradiction the skill mechanic cannot produce, and throws the whole memory away when it comes
     * - see {@link #discardRecall}.
     */
    private boolean adopt(Connection[][] remembered, String why) {
        if (recallAdopted || remembered.length != n || !LockModel.isComplete(cur)) {
            return false;
        }
        for (Connection[] row : remembered) {
            if (row == null) {
                return false;
            }
        }
        LockModel candidate = new LockModel(n, cur, remembered, LockModel.MAX_OFFSET);
        if (LockSolver.solve(candidate, cur, keys.cursor(), Cost.WALLCLOCK) == null) {
            trace("recall: a remembered model does not open the lock from " + Arrays.toString(cur)
                    + "; ignoring it and probing as usual");
            return false;
        }
        // Both callers only reach here with at least one row still unprobed, so this always adopts
        // something; rows already probed for real are skipped, because an observation outranks a memory.
        int adopted = 0;
        for (int p = 0; p < n; p++) {
            if (conn[p] != null) {
                continue;
            }
            conn[p] = remembered[p].clone();
            fromRecall[p] = true;
            adopted++;
        }
        plan = null;
        recallAdopted = true;
        everRecalled = true;
        reporter.recognised(why, adopted);
        trace(why + ": adopted " + adopted + " remembered row(s) without probing");
        traceModel();
        return true;
    }

    /**
     * Throws away a remembered model the lock has disproved, along with every row still resting on
     * it, and lets discovery start over from what the lock has actually shown.
     *
     * <p>Called on exactly the contradictions the skill mechanic cannot account for, because those
     * are the ones that prove the memory is of <b>another chest</b> rather than of this one at a
     * higher level: a strain on a slide the memory called legal (a superset cannot make a legal move
     * strain), and an observed row that <i>adds</i> a connection the memory lacks (training only ever
     * removes one). Either way the offsets collided.
     *
     * <p>Dropping the <b>whole</b> memory rather than the one row it was caught on is the point. The
     * rows left behind are fiction about a different lock, and {@link #planUnblock} and
     * {@link #repositionForFreshGamble} search over exactly the plates whose rows are "known" - so a
     * single surviving lie can leave both searches unable to find any legal move at all, and the run
     * reports a lock that will not budge on a lock it read perfectly. That is the live failure this
     * exists for.
     *
     * <p>What is kept: every row this run observed for itself, every refusal, every deduction. Those
     * are facts about the lock on screen, and nothing here casts doubt on them.
     */
    private void discardRecall() {
        int dropped = 0;
        for (int p = 0; p < n; p++) {
            if (fromRecall[p]) {
                conn[p] = null;
                fromRecall[p] = false;
                dropped++;
            }
        }
        if (dropped == 0) {
            // The row that gave the memory away was the last one still resting on it, so there is
            // nothing left to drop and nothing to say. recallAdopted stays true either way.
            return;
        }
        // recallAdopted stays true: this run is done with the catalogue, and probes. See the field.
        plan = null;
        corrections++; // a fact changed - re-plan, and reset the loop guard
        reporter.recallWrongChest(dropped);
        trace("recall: the memory is of a different chest sharing these offsets; dropped " + dropped
                + " remembered row(s) and probing from here");
    }

    /**
     * The way back when F8 was pressed on a lock that is no longer pristine - a retry after a run
     * that stopped early. The offsets key nothing then, but a row this run has probed for itself is
     * a fingerprint of its own: measured over the corpus, one row cuts a six-plate lock from 58
     * remembered locks to about four, and names it outright 39% of the time. Adopted only when
     * exactly one candidate is left, so an ambiguous match keeps probing.
     */
    private void recogniseFromProbedRows() {
        if (recallAdopted || allProbed()) {
            return;
        }
        Map<Integer, Connection[]> observed = new HashMap<>();
        for (int p = 0; p < n; p++) {
            if (conn[p] != null && !fromRecall[p]) {
                observed.put(p, conn[p]);
            }
        }
        if (observed.isEmpty()) {
            return;
        }
        List<Connection[][]> fits = known.matching(n, observed);
        if (fits.size() == 1) {
            adopt(fits.get(0), "one lock I have opened before matches every row probed so far");
        }
    }

    private int probedCount() {
        int probed = 0;
        for (Connection[] row : conn) {
            if (row != null) {
                probed++;
            }
        }
        return probed;
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
                ^ (((long) occluded.size()) << 24) ^ (((long) corrections) << 16)
                ^ (((long) deductions) << 12) ^ (probed << 8);
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
                if (learn(p, before, cur)) {
                    advancePlan(move);
                } else {
                    plan = null; // the frame was a misread, not a fact; re-plan from a fresh look
                }
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
            deduceFrom(p, dir, before);
        }
        cur = obs.state();
        // The game may have moved the selection - it re-homes it whenever a pick breaks, and a break
        // is invisible above skill level 0. Saturating S is right either way, at ~10ms a press.
        keys.endCursor(n);

        // The model's correction comes FIRST, before the break is handled. A pick breaking is not a
        // reason to go on believing a row the lock has just disproved, and this used to return above:
        // the row survived, the next pass re-solved to the same plan, and the identical move was
        // replayed for a second strain. (Seen live: two strains and a pick on one refused slide.)
        boolean rowDropped = conn[p] != null;
        if (rowDropped) {
            // The model swore this was legal. It is the model that is wrong, so re-probe p.
            reporter.refusedLegalMove(p);
            boolean remembered = fromRecall[p];
            conn[p] = null;
            fromRecall[p] = false;
            corrections++;
            if (remembered && !contradictory) {
                // A memory of THIS chest could only be a superset of the truth, and a superset cannot
                // make a legal move strain. So this is a different chest showing the same offsets,
                // and everything else still merely remembered is fiction about it.
                discardRecall();
            }
        }

        if (obs.pickBroke()) {
            recordBreak(obs.outcome() == MoveExecutor.Outcome.RESET);
            reporter.pickBroke(strainsPerPick, observedBreaks,
                    obs.outcome() == MoveExecutor.Outcome.RESET, breakResetThePuzzle, cur, rowDropped);
            return;
        }
        if (rowDropped) {
            return; // already reported, above
        }
        if (contradictory) {
            // Nothing was at an end, so don't retry this exact slide from this exact configuration;
            // once some plate moves, the misread may resolve and it becomes worth another look.
            occluded.add(new Occlusion(p, dir, LockSolver.encode(model(), before)));
            reporter.contradictoryStrain(p, dir, misreadStrains);
        } else {
            reporter.willNotSlideYet(p, dir, strains);
        }
    }

    /**
     * What a strain proves. The slide was rejected, and the only way a slide can be rejected is by
     * dragging some plate off the end of its track - so the culprit is among the plates parked at an
     * end, and each of those could only be the culprit if dragged one particular way (a plate at the
     * left end goes off only by moving further left). When exactly one plate qualifies, that is not a
     * suspicion: {@code p} drags it, that way, and the run keeps the fact.
     *
     * <p>A third of the strains in the corpus are that clear-cut, and half the rest narrow to two
     * candidates - which is left alone here, because a disjunction is not a connection. The strain was
     * paid for either way; this is only refusing to throw the receipt away.
     */
    private void deduceFrom(int p, int dir, int[] state) {
        Connection only = null;
        for (int q = 0; q < n; q++) {
            if (q == p || Math.abs(state[q]) != LockModel.MAX_OFFSET) {
                continue;
            }
            if (only != null) {
                return; // more than one plate could have been the one pushed off; nothing is settled
            }
            boolean atLeftEnd = state[q] == LockModel.MAX_OFFSET;
            only = new Connection(q, (atLeftEnd == (dir > 0))
                    ? Connection.Type.NORMAL : Connection.Type.INVERTED);
        }
        if (only == null || List.of(deduced[p]).contains(only)) {
            return;
        }
        List<Connection> row = new ArrayList<>(List.of(deduced[p]));
        row.add(only);
        deduced[p] = row.toArray(new Connection[0]);
        deductions++;
        reporter.deducedFromStrain(p, only);
        trace("deduced from the strain: plate " + p + " drags "
                + SessionReporter.describe(new Connection[] {only}));
    }

    /**
     * True when something already deduced about {@code p} guarantees this slide strains. Stronger
     * than the refusal memory, which only recognises the same slide from a configuration where every
     * plate that was at an end still is: a deduced connection rules the move out from <b>any</b>
     * configuration, and in the direction never tried as well.
     */
    private boolean certainlyStrains(int[] state, int p, int dir) {
        for (Connection c : deduced[p]) {
            if (Math.abs(state[c.target()] + dir * c.type().sign()) > LockModel.MAX_OFFSET) {
                return true;
            }
        }
        return false;
    }

    /**
     * A successful move reveals exactly what {@code p} drags - which also repairs a wrong row. Returns
     * false, learning nothing, when the frame is physically impossible: the plate we slid reads as not
     * having moved at all, though the game accepted the slide. That is a misread, and a zero reference
     * would brand every other plate inverted, so the row is left as it was for a re-probe.
     */
    private boolean learn(int p, int[] before, int[] after) {
        int moverDelta = after[p] - before[p];
        if (moverDelta == 0) {
            return false; // impossible read of the plate we moved; do not fold it into the model
        }
        List<Connection> row = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            if (q == p || after[q] == before[q]) continue;
            Connection.Type type = Integer.signum(after[q] - before[q]) == Integer.signum(moverDelta)
                    ? Connection.Type.NORMAL : Connection.Type.INVERTED;
            row.add(new Connection(q, type));
        }
        boolean first = conn[p] == null;
        Connection[] learned = row.toArray(new Connection[0]);
        // What this reading disagrees with: a row already in hand, or - if the plate was just cleared for
        // a recovery re-probe - the row that recovery threw away. Either way a change is real progress.
        Connection[] previous = first ? preRecoveryRow.remove(p) : conn[p];
        boolean wasRemembered = fromRecall[p];
        boolean memoryGainedALink = false;
        if (previous != null && !Arrays.equals(previous, learned)) {
            if (wasRemembered) {
                // Not a misread: most often the character has trained since this lock was recorded,
                // which removes a plate connection. Say which, then take what the lock just did.
                reporter.recallCorrected(p, previous, learned);
                // But training only ever REMOVES one. A row that comes back with a connection the
                // memory does not have cannot be this chest at any level, so the memory is of another
                // chest that happens to show the same offsets - see discardRecall.
                memoryGainedALink = !Connection.rowContains(previous, learned);
            }
            plan = null; // the model the plan was built on was wrong about this plate
            corrections++; // a fact changed - progress, for the loop guard
            contested[p] = true; // this plate read two different ways: a prime misread suspect
            recoveryTried.clear(); // the model just improved; let recovery reconsider every plate
        }
        conn[p] = learned;
        fromRecall[p] = false; // seen for real now; no longer something merely remembered
        if (memoryGainedALink) {
            discardRecall(); // p's row is already observed, so this only clears the ones still guessed
        }
        deduced[p] = new Connection[0]; // the whole row is in hand; the partial one has nothing to add
        observationCount[p]++;
        if (first) {
            inEscalation = false; // a plate newly probed is real progress; leave the escalation regime
            reporter.probed(p, conn[p], allProbed());
        }
        return true;
    }

    // --- recovering from a model that will not open ---

    /**
     * A fully-probed model the solver cannot open means a misread corrupted one plate's row - the game
     * only ever hands out solvable locks. So rather than give up, find the plate whose row could be
     * edited to a solvable model (the likeliest culprit), clear it, and let discovery re-probe it in
     * the configuration the lock is now in - different from where it was first learned, so a
     * configuration-specific misread reads differently this time. The edit only <i>names</i> the
     * suspect; the fresh probe, not the edit, is what is trusted, and every later solving move stays
     * verified besides. Bounded by {@link #MAX_RECOVERY_RESETS} and {@link #MAX_RECOVERY_PICKS} so a
     * multiply-corrupted read still stops and dumps rather than eating picks.
     */
    private boolean tryRecoverUnsolvable() {
        if (recoveryResets >= MAX_RECOVERY_RESETS) {
            return false;
        }
        if (breaksWhenRecoveryBegan >= 0
                && observedBreaks - breaksWhenRecoveryBegan >= MAX_RECOVERY_PICKS) {
            return false;
        }
        int suspect = suspectPlate();
        if (suspect < 0) {
            return false;
        }
        if (breaksWhenRecoveryBegan < 0) {
            breaksWhenRecoveryBegan = observedBreaks;
        }
        preRecoveryRow.put(suspect, conn[suspect]);
        recoveryTried.add(suspect);
        conn[suspect] = null; // re-enables discovery, which will re-probe it (nextAction gates on allProbed)
        plan = null;
        corrections++; // a suspect cleared is progress: reconsider the plan, reset the loop guard
        recoveryResets++;
        reporter.reProbing(suspect);
        trace("recovery: re-probing plate " + suspect + " (no solution from " + Arrays.toString(cur)
                + "), reset " + recoveryResets + "/" + MAX_RECOVERY_RESETS);
        return true;
    }

    /**
     * The plate most likely misread: among those a single row-edit could make solvable and not already
     * re-probed unproductively, prefer a contested one, then the most minimal edit (a flipped
     * connection over a dropped or added one), then the least-confirmed row. Returns -1 when no single
     * edit opens the lock - a read corrupted in more than one place, which recovery cannot pick apart.
     */
    private int suspectPlate() {
        LockModel base = model();
        int best = -1;
        int bestRank = Integer.MAX_VALUE;
        for (int p = 0; p < n; p++) {
            if (recoveryTried.contains(p)) {
                continue;
            }
            int rank = ModelRepair.singleEditRank(base, cur, p);
            if (rank == Integer.MAX_VALUE) {
                continue;
            }
            if (best < 0 || betterSuspect(p, rank, best, bestRank)) {
                best = p;
                bestRank = rank;
            }
        }
        return best;
    }

    /** True if plate {@code p} (fixable at {@code rank}) is a better suspect than the incumbent. */
    private boolean betterSuspect(int p, int rank, int incumbent, int incumbentRank) {
        if (contested[p] != contested[incumbent]) {
            return contested[p];
        }
        if (rank != incumbentRank) {
            return rank < incumbentRank;
        }
        return observationCount[p] < observationCount[incumbent];
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
                reporter.fillingHiddenRow();
                cur = predicted;
                return;
            }
            reporter.hiddenRowDiscardKnown(p);
            conn[p] = null;
        } else {
            reporter.undoingHidRow(p);
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
            reporter.nudgingHiddenRow(nudge.plate());
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
            // No refusal check here, deliberately - see "The solving tier needs no refusal gate" in
            // CLAUDE.md's dead ends. Every discovery tier screens its move through worthTrying, and
            // it looks like an oversight that this one does not; it is not. A refusal is only ever
            // recorded in step(), which clears the mover's row in the same breath, so the moment a
            // slide is refused its plate is unprobed and there is no plan to propose it again.
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
            reporter.noSolution(cur);
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
                reporter.clearingEnds(p, plan.size());
                return plan.get(0);
            }
        }
        return null;
    }

    /**
     * Press a key and accept the risk - but the smallest risk available, and as a number rather than
     * a proxy for one. {@link ConnectionPrior} costs out every unprobed plate against every direction
     * from the offsets on screen, using what 124 real locks say about how many connections a lock has
     * and which way they run.
     *
     * <p>It replaces "fewest other plates at an end, then toward centre", which could not tell a 34%
     * gamble from a 40% one - and those are the real odds of the same slide taken the two ways round
     * on a fresh six-plate lock, because INVERTED is the commoner connection. Toward centre survives
     * as the tie-break: it still walks a plate off an end and makes every later move safer.
     */
    private Move gamble() {
        ConnectionPrior prior = ConnectionPrior.from(n, conn);
        Move best = null;
        double bestRisk = Double.MAX_VALUE;
        boolean bestTowardCentre = false;
        for (int p = 0; p < n; p++) {
            if (conn[p] != null) {
                continue;
            }
            int toward = cur[p] > 0 ? -1 : +1;
            for (int dir : new int[] {toward, -toward}) {
                if (!worthTrying(cur, p, dir)) {
                    continue;
                }
                double risk = prior.strainRisk(cur, p, dir, deduced[p]);
                boolean towardCentre = dir == toward;
                if (best == null || risk < bestRisk - 1e-9
                        || (risk < bestRisk + 1e-9 && towardCentre && !bestTowardCentre)) {
                    best = new Move(p, dir);
                    bestRisk = risk;
                    bestTowardCentre = towardCentre;
                }
            }
        }
        if (best != null) {
            trace(String.format(Locale.ROOT, "gamble: plate %d %s, %.0f%% chance of straining",
                    best.plate(), best.dir() > 0 ? "left" : "right", bestRisk * 100));
        }
        return best;
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
            if (worthTrying(state, p, dir)) return dir;
        }
        return 0;
    }

    /**
     * Whether this slide is still worth a key: it keeps {@code p} on its own track, it is not one the
     * refusal memory has ruled out, it is not known to hide a row from here, and nothing already
     * deduced about {@code p} guarantees it strains.
     */
    private boolean worthTrying(int[] state, int p, int dir) {
        return Math.abs(state[p] + dir) <= LockModel.MAX_OFFSET
                && !isRefused(state, p, dir)
                && !isOccludedAt(state, p, dir)
                && !certainlyStrains(state, p, dir);
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
        reporter.repositioning(setup.size());
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

    /** The one solved-report line, from wherever the run concludes the lock is open. */
    private void reportSolved() {
        solved = true;
        reporter.solved(strains, picksSpent(), strainsPerPick, breakResetThePuzzle);
    }

    /** Whether this run opened the lock. False on any give-up, and before {@link #run()} concludes. */
    public boolean solved() {
        return solved;
    }

    /**
     * The character's lockpicking level, if this run happened to reveal it - which only a broken pick
     * does, so a clean run reveals nothing and says so. Observed, never configured: the player can
     * train between one lock and the next.
     *
     * <p>It is worth writing into the solve history because the level changes the <b>lock</b>, not
     * only the pick: Trained removes one plate connection and Master removes another. A model recorded
     * untrained is the maximal one, and that is the only kind recall can lean on freely.
     */
    public Optional<Skill> observedSkill() {
        if (breakResetThePuzzle) {
            return Optional.of(Skill.UNTRAINED); // only an untrained break sends the plates home
        }
        return Skill.fromStrainsPerPick(strainsPerPick);
    }

    /** The offsets read the instant F8 was pressed, or null if no lock was ever detected. */
    public int[] initialState() {
        return initialState == null ? null : initialState.clone();
    }

    /** A copy of the learned model: {@code [p]} is what moving p drags (null while unprobed). */
    public Connection[][] connections() {
        if (conn == null) {
            return new Connection[0][];
        }
        Connection[][] copy = new Connection[conn.length][];
        for (int p = 0; p < conn.length; p++) {
            copy[p] = conn[p] == null ? null : conn[p].clone();
        }
        return copy;
    }

    private void unreadable(String when) {
        reporter.unreadable(when);
        view.dumpFrame("unreadable");
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
}
