package io.github.markosa84.colonysskeletonkey.session;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 *   <li><b>Gamble</b> - only when neither exists. Fewest plates at ends first, sliding toward centre
 *       so plates walk off the ends and the next move is safer.</li>
 * </ol>
 * A gamble that strains is remembered and never retried while the plate that blocked it could still be
 * blocking it ({@link #isRefused}). <b>That memory survives a broken pick</b>, which is what stops the
 * reset from recreating the very gamble that just failed - the old failure mode where a hard lock ate
 * every pick in the player's inventory.
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
 * is never concluded from hole rows at all: the goal is confirmed from the pin pops, which no
 * hole-row artifact can touch.
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

    public LockSession(LockView view, CursorKeys keys, MoveExecutor mover) {
        this.view = view;
        this.keys = keys;
        this.mover = mover;
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

        try {
            loop();
        } catch (MoveExecutor.UnreadableFrame e) {
            if (solved()) {
                report("Lock solved");
            } else {
                unreadable("mid-run");
            }
        }
    }

    private void loop() {
        while (true) {
            if (!recoverFull()) {
                System.out.println("Stuck: a plate's hole row stays hidden after every nudge. "
                        + "Slide any plate one step by hand and press F8 again.");
                return;
            }
            if (LockSolver.isGoal(cur)) {
                // Hole rows can be hidden or model-filled; the pin pops can be neither. Only they
                // may declare the lock open.
                boolean[] popped = view.readCentered(n);
                if (allTrue(popped)) {
                    report("Lock solved");
                    return;
                }
                for (int i = 0; i < n; i++) {
                    if (!popped[i]) cur[i] = LockModel.UNKNOWN; // that belief was wrong; re-read
                }
                continue;
            }
            if (picksSpent() >= MAX_PICKS) {
                System.out.println("Giving up after " + strains + " strain(s), about " + picksSpent()
                        + " broken pick(s). Plates " + unprobed() + " never moved. Every lock in the "
                        + "game opens well inside this, so the fault is here, not in the lock.");
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
                System.out.println("Stuck: no move is left to try. Plates " + unprobed()
                        + " will not budge in either direction, and no probed plate can free them.");
                return;
            }
            step(action);
        }
    }

    /** Plays one move and folds what happened back into what we know. */
    private void step(Move move) {
        int p = move.plate();
        int dir = move.dir();
        int[] before = cur;
        MoveExecutor.Observation obs = mover.play(n, before, move);

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
        refuse(before, p, dir);
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
        }
        conn[p] = learned;
        if (first) {
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
                // Every further move keeps re-verifying, and only the pops declare the goal.
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

    private static boolean allTrue(boolean[] flags) {
        for (boolean f : flags) {
            if (!f) return false;
        }
        return true;
    }

    // --- choosing the next move ---

    /** The next move to play: a discovery move while anything is unprobed, otherwise a solving move. */
    private Move nextAction() {
        if (allProbed()) {
            return solvingMove();
        }
        Move free = freeMove();
        if (free != null) return free;

        Move planned = plannedMove();
        if (planned != null) return planned;

        return gamble();
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

    /** Toward centre if that is still worth trying, else away from it, else 0. */
    private int pickDirection(int p) {
        int toward = cur[p] > 0 ? -1 : +1;
        for (int dir : new int[] {toward, -toward}) {
            if (Math.abs(cur[p] + dir) > LockModel.MAX_OFFSET) continue; // p itself would fall off
            if (isRefused(cur, p, dir)) continue;
            if (isOccludedHere(p, dir)) continue;
            return dir;
        }
        return 0;
    }

    /** True if this exact move, from this exact configuration, is known to hide a row. */
    private boolean isOccludedHere(int p, int dir) {
        long key = LockSolver.encode(model(), cur);
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

    private void refuse(int[] state, int p, int dir) {
        refused.add(new Refusal(p, dir, ends(state, +1, p), ends(state, -1, p)));
        strained();
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

    /** Confirms the lock is open from the pin pops alone, which need no hole rows. */
    private boolean solved() {
        return allTrue(view.readCentered(n));
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
