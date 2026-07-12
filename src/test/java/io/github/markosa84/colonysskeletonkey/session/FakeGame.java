package io.github.markosa84.colonysskeletonkey.session;

import java.util.ArrayList;
import java.util.List;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.Move;

/**
 * A simulated game for {@link LockSession} tests: one object implements all three of the
 * session's seams over a hidden truth model, with real {@link LockSolver#applyMove} semantics.
 *
 * <p>An illegal move strains, every {@code skill.mistakes()}-th strain breaks the pick, and at
 * {@code UNTRAINED} a break resets the puzzle to its start - exactly the game's rules. The
 * session never sees the truth model; it only observes, as in the real game.
 */
final class FakeGame implements LockView, MoveExecutor, CursorKeys {

    private final LockModel truth;
    private final Skill skill;

    int[] state;
    int cursor;
    int plays;
    int strains;
    int breaks;
    int endCursorCalls;
    final List<String> dumps = new ArrayList<>();

    /** Play number (1-based) at which one legal move is falsely reported as a strain, or -1. */
    int lieAtPlay = -1;

    /**
     * Play number (1-based) whose frame cannot be read at all, or -1. The move still <b>lands</b> -
     * the game does not care that we failed to look at it - which is exactly how a live misread
     * behaves.
     */
    int unreadableAtPlay = -1;

    /**
     * When set, the frame after the move that opens the lock is unreadable: the minigame is closing
     * and there is nothing left on screen to read. The pins are still the truth.
     */
    boolean unreadableOnceOpen;

    /** While {@code hideWhen} matches the true state, plate {@code hiddenPlate} reads UNKNOWN. */
    int hiddenPlate = -1;
    java.util.function.Predicate<int[]> hideWhen = s -> false;

    FakeGame(LockModel truth, Skill skill) {
        this.truth = truth;
        this.skill = skill;
        this.state = truth.start().clone();
        this.cursor = truth.n() - 1; // the game parks the selection on the last plate
    }

    boolean opened() {
        return LockSolver.isGoal(state);
    }

    // --- LockView ---

    @Override
    public int detectPlateCount() {
        return truth.n();
    }

    @Override
    public boolean[] readCentered(int n) {
        boolean[] centered = new boolean[n];
        for (int i = 0; i < n; i++) {
            centered[i] = state[i] == 0;
        }
        return centered;
    }

    @Override
    public void dumpFrame(String tag) {
        dumps.add(tag);
    }

    // --- MoveExecutor ---

    @Override
    public Observation play(int n, int[] cur, Move move) {
        plays++;
        cursor = move.plate(); // the executor navigates to the plate before sliding
        if (plays == lieAtPlay) {
            return new Observation(Outcome.UNCHANGED, cur.clone(), false); // a phantom strain
        }
        int[] next = LockSolver.applyMove(truth, state, move.plate(), move.dir());
        if (next != null) {
            state = next;
            if (plays == unreadableAtPlay || (unreadableOnceOpen && LockSolver.isGoal(state))) {
                throw new UnreadableFrame(); // the move landed; the frame after it did not read
            }
            return new Observation(Outcome.MOVED, mask(state), false);
        }
        strains++;
        if (strains % skill.mistakes() == 0) {
            breaks++;
            cursor = n - 1; // the game re-homes the selection when a pick breaks
            if (skill.resetsOnBreak()) {
                state = truth.start().clone();
                return new Observation(Outcome.RESET, mask(state), true);
            }
            return new Observation(Outcome.UNCHANGED, mask(state), true);
        }
        // A rejected move changes nothing, so the caller's pre-move state stands in full -
        // exactly the real executor's contract.
        return new Observation(Outcome.UNCHANGED, cur.clone(), false);
    }

    @Override
    public int[] settle(int n) {
        return mask(state);
    }

    /** What the screen shows: the truth, minus any plate whose row is currently hidden. */
    private int[] mask(int[] s) {
        int[] out = s.clone();
        if (hiddenPlate >= 0 && hideWhen.test(s)) {
            out[hiddenPlate] = LockModel.UNKNOWN;
        }
        return out;
    }

    // --- CursorKeys ---

    @Override
    public void endCursor(int n) {
        endCursorCalls++;
        cursor = n - 1;
    }

    @Override
    public int cursor() {
        return cursor;
    }
}
