package io.github.markosa84.colonysskeletonkey.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.solver.Connection.Type;

/**
 * Direct unit tests for the model-repair math, which used to live inside {@code LockSession} and was
 * exercised only through end-to-end session runs. Here the pure functions are pinned on their own:
 * the row-edit generators produce exactly the rows they promise, {@link ModelRepair#reachesGoal}
 * agrees with {@link LockSolver} on solvability, and {@link ModelRepair#singleEditRank} finds the
 * minimal fix - or reports none when the corruption is not a single edit.
 */
class ModelRepairTest {

    // --- The mutual pair from LockSessionTest's recovery scenario, as a self-contained fixture.
    //
    // Truth: moving plate 0 drags plate 1 the same way, moving plate 1 drags plate 0 the opposite
    // way. From {2, -2, 0, 0} that pair can be walked to centre, so the lock opens.
    private static final int[] START = {2, -2, 0, 0};
    private static final LockModel TRUTH =
            LockModel.of(START, new Connection[][] {row(n(1)), row(i(0)), row(), row()});
    // The probing misread: plate 1's drag of plate 0 is learned NORMAL instead of INVERTED. Now the
    // pair moves rigidly and their offset difference (4) can never change, so the lock cannot open.
    private static final LockModel MISREAD =
            LockModel.of(START, new Connection[][] {row(n(1)), row(n(0)), row(), row()});

    @Test
    void reachesGoalAgreesWithTheSolverOnSolvability() {
        assertTrue(ModelRepair.reachesGoal(TRUTH, START), "the true model opens from the start");
        assertTrue(LockSolver.solve(TRUTH, START, TRUTH.n() - 1, Cost.WALLCLOCK) != null,
                "the solver agrees the true model opens");
        assertFalse(ModelRepair.reachesGoal(MISREAD, START), "the misread model cannot open");
        assertTrue(LockSolver.solve(MISREAD, START, MISREAD.n() - 1, Cost.WALLCLOCK) == null,
                "the solver agrees the misread model is stuck");
    }

    @Test
    void singleEditRankFlipsBackTheMisreadConnection() {
        // Flipping plate 1's single connection back to INVERTED restores the true model: rank 0.
        assertEquals(0, ModelRepair.singleEditRank(MISREAD, START, 1));
    }

    @Test
    void singleEditRankReportsNoFixForAnInnocentPlate() {
        // No edit to plate 2 (which drags nothing) can undo the corruption on the 0/1 pair, so the
        // difference stays invariant however plate 2 is wired: there is no single edit here.
        assertEquals(Integer.MAX_VALUE, ModelRepair.singleEditRank(MISREAD, START, 2));
    }

    @Test
    void flipEachFlipsExactlyOneConnectionPerRow() {
        List<Connection[]> out = ModelRepair.flipEach(row(n(1), i(2)));
        assertEquals(2, out.size());
        assertRow(out.get(0), i(1), i(2));
        assertRow(out.get(1), n(1), n(2));
    }

    @Test
    void dropEachRemovesExactlyOneConnectionPerRow() {
        List<Connection[]> out = ModelRepair.dropEach(row(n(1), i(2), n(3)));
        assertEquals(3, out.size());
        assertRow(out.get(0), i(2), n(3));
        assertRow(out.get(1), n(1), n(3));
        assertRow(out.get(2), n(1), i(2));
    }

    @Test
    void flipAllReversesEveryConnection() {
        assertRow(ModelRepair.flipAll(row(n(1), i(2))), i(1), n(2));
    }

    @Test
    void addEachAddsEveryAbsentTargetBothWays() {
        // row on plate 0 already drags plate 1; the absent targets are 2 and 3, each as N and I.
        List<Connection[]> out = ModelRepair.addEach(4, 0, row(n(1)));
        assertEquals(4, out.size());
        assertRow(out.get(0), n(1), n(2));
        assertRow(out.get(1), n(1), i(2));
        assertRow(out.get(2), n(1), n(3));
        assertRow(out.get(3), n(1), i(3));
    }

    @Test
    void modelWithReplacesOnlyTheNamedRow() {
        LockModel edited = ModelRepair.modelWith(TRUTH, 1, row(i(0), n(2)));
        assertRow(edited.connections()[1], i(0), n(2));
        assertRow(edited.connections()[0], n(1)); // every other row untouched
        assertEquals(TRUTH.n(), edited.n());
    }

    // --- helpers ---

    private static Connection n(int target) {
        return new Connection(target, Type.NORMAL);
    }

    private static Connection i(int target) {
        return new Connection(target, Type.INVERTED);
    }

    private static Connection[] row(Connection... connections) {
        return connections;
    }

    private static void assertRow(Connection[] actual, Connection... expected) {
        assertTrue(Arrays.equals(actual, expected),
                "expected " + Arrays.toString(expected) + " but was " + Arrays.toString(actual));
    }
}
