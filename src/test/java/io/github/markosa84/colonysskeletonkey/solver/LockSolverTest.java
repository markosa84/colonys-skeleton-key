package io.github.markosa84.colonysskeletonkey.solver;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The solver core: the connection algebra of {@link LockSolver#applyMove}, the state encoding,
 * and the search - validity (every emitted move is legal), optimality (no plan uses more slides
 * than a brute-force fewest-slides search), and the documented agreement between the
 * {@link Cost#KEYPRESS} and {@link Cost#WALLCLOCK} weightings.
 */
class LockSolverTest {

    // --- the connection algebra ---

    @Test
    void normalConnectionDragsTheSameDirection() {
        LockModel m = LockModel.of(new int[] {0, 0}, rows(row(n(1)), row()));
        assertArrayEquals(new int[] {1, 1}, LockSolver.applyMove(m, m.start(), 0, +1));
    }

    @Test
    void invertedConnectionDragsTheOppositeDirection() {
        LockModel m = LockModel.of(new int[] {0, 0}, rows(row(i(1)), row()));
        assertArrayEquals(new int[] {1, -1}, LockSolver.applyMove(m, m.start(), 0, +1));
    }

    @Test
    void connectionsDoNotCascade() {
        // Moving 0 drags 1; plate 1's own row (which would drag 2) must not fire.
        LockModel m = LockModel.of(new int[] {0, 0, 0}, rows(row(n(1)), row(n(2)), row()));
        assertArrayEquals(new int[] {1, 1, 0}, LockSolver.applyMove(m, m.start(), 0, +1));
    }

    @Test
    void moveIsAtomicWhenADraggedPlateWouldLeaveItsTrack() {
        LockModel m = LockModel.of(new int[] {0, 3}, rows(row(n(1)), row()));
        assertNull(LockSolver.applyMove(m, m.start(), 0, +1), "dragging plate 1 off +3 must fail");
        assertArrayEquals(new int[] {-1, 2}, LockSolver.applyMove(m, m.start(), 0, -1),
                "the same plates moving away from the wall are legal");
    }

    @Test
    void pressingAPlateIntoItsOwnWallIsIllegal() {
        LockModel m = LockModel.of(new int[] {3}, rows(row()));
        assertNull(LockSolver.applyMove(m, m.start(), 0, +1));
        assertNotNull(LockSolver.applyMove(m, m.start(), 0, -1));
    }

    @Test
    void encodeIsUniqueAcrossTheWholeStateSpace() {
        LockModel m = LockModel.of(new int[] {0, 0, 0}, rows(row(), row(), row()));
        Set<Long> keys = new HashSet<>();
        int span = 2 * m.maxOffset() + 1;
        int[] state = new int[3];
        for (int a = -3; a <= 3; a++) {
            for (int b = -3; b <= 3; b++) {
                for (int c = -3; c <= 3; c++) {
                    state[0] = a;
                    state[1] = b;
                    state[2] = c;
                    assertTrue(keys.add(LockSolver.encode(m, state)), "duplicate key");
                }
            }
        }
        assertEquals(span * span * span, keys.size());
    }

    // --- the search ---

    /** The 6-plate example that used to live in the manual solver mode's constants. */
    private static LockModel sixPlateExample() {
        return LockModel.of(
                new int[] {-3, -2, 3, -2, -3, -1},
                rows(row(n(2)),
                     row(i(0), n(3)),
                     row(),
                     row(),
                     row(n(5), i(3)),
                     row(n(0), n(2))));
    }

    @Test
    void solvesTheDocumentedSixPlateExample() {
        LockModel m = sixPlateExample();
        List<Move> plan = LockSolver.solve(m);
        assertNotNull(plan);
        assertTrue(LockSolver.isGoal(replay(m, m.start(), plan)));
    }

    @Test
    void returnsNullWhenTheCenteredStateIsUnreachable() {
        // A mutual Normal pair keeps the two offsets' difference constant; unequal offsets can
        // never both reach 0.
        LockModel m = LockModel.of(new int[] {0, 1}, rows(row(n(1)), row(n(0))));
        assertNull(LockSolver.solve(m));
    }

    @Test
    void solvesFromAnArbitraryConfigurationAndCursor() {
        LockModel m = sixPlateExample();
        int[] elsewhere = LockSolver.applyMove(m, m.start(), 2, -1); // plate 2 starts at +3
        assertNotNull(elsewhere);
        List<Move> plan = LockSolver.solve(m, elsewhere, 0, Cost.KEYPRESS);
        assertNotNull(plan);
        assertTrue(LockSolver.isGoal(replay(m, elsewhere, plan)));
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    void plansUseTheFewestSlidesPossible(long seed) {
        LockModel m = TestLocks.solvable(seed, 4 + (int) (seed % 2), 12);
        List<Move> plan = LockSolver.solve(m);
        assertNotNull(plan, "scrambled from the goal, so a solution must exist");
        assertTrue(LockSolver.isGoal(replay(m, m.start(), plan)));
        assertEquals(fewestSlides(m), plan.size(),
                "the keypress-optimal plan must already use the fewest slides");
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    void keypressAndWallclockWeightingsAgree(long seed) {
        LockModel m = TestLocks.solvable(100 + seed, 6, 15);
        List<Move> byKeys = LockSolver.solve(m, m.start(), m.n() - 1, Cost.KEYPRESS);
        List<Move> byTime = LockSolver.solve(m, m.start(), m.n() - 1, Cost.WALLCLOCK);
        assertNotNull(byKeys);
        assertNotNull(byTime);
        assertTrue(LockSolver.isGoal(replay(m, m.start(), byKeys)));
        assertTrue(LockSolver.isGoal(replay(m, m.start(), byTime)));
        // The documented fact: a redundant slide can never buy back navigation, so the two
        // weightings produce equally long and equally cheap plans.
        assertEquals(byKeys.size(), byTime.size(), "slide counts must agree");
        assertEquals(keypresses(byKeys, m.n() - 1), keypresses(byTime, m.n() - 1),
                "total keypress costs must agree");
    }

    @Test
    void isGoalIsAllZeros() {
        assertTrue(LockSolver.isGoal(new int[] {0, 0, 0}));
        assertFalse(LockSolver.isGoal(new int[] {0, -1, 0}));
    }

    // --- helpers ---

    /** Plays the plan through {@link LockSolver#applyMove}, failing on any illegal move. */
    private static int[] replay(LockModel m, int[] start, List<Move> plan) {
        int[] state = start;
        for (Move move : plan) {
            state = LockSolver.applyMove(m, state, move.plate(), move.dir());
            assertNotNull(state, "the plan contains an illegal move: " + move);
        }
        return state;
    }

    /** Keys a human would press to play the plan from {@code startCursor}: W/S travel plus A/D. */
    private static int keypresses(List<Move> plan, int startCursor) {
        int cursor = startCursor;
        int keys = 0;
        for (Move move : plan) {
            keys += Math.abs(move.plate() - cursor) + 1;
            cursor = move.plate();
        }
        return keys;
    }

    /** Brute-force fewest slides to the goal, ignoring the cursor entirely (plain BFS). */
    private static int fewestSlides(LockModel m) {
        Map<Long, Integer> depth = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        depth.put(LockSolver.encode(m, m.start()), 0);
        queue.add(m.start());
        while (!queue.isEmpty()) {
            int[] state = queue.poll();
            int d = depth.get(LockSolver.encode(m, state));
            if (LockSolver.isGoal(state)) return d;
            for (int p = 0; p < m.n(); p++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int[] next = LockSolver.applyMove(m, state, p, dir);
                    if (next == null) continue;
                    if (depth.putIfAbsent(LockSolver.encode(m, next), d + 1) == null) {
                        queue.add(next);
                    }
                }
            }
        }
        throw new AssertionError("no solution found by BFS");
    }

    private static Connection n(int target) {
        return new Connection(target, NORMAL);
    }

    private static Connection i(int target) {
        return new Connection(target, INVERTED);
    }

    private static Connection[] row(Connection... connections) {
        return connections;
    }

    private static Connection[][] rows(Connection[]... rows) {
        return rows;
    }
}
