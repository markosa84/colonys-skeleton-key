package io.github.markosa84.colonysskeletonkey.solver;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Seeded random locks for the solver and session tests. */
public final class TestLocks {

    private TestLocks() {}

    /**
     * A random lock whose start was reached by playing {@code scrambleMoves} random legal moves
     * from the all-centered goal. Every move is reversible (a legal move's inverse is legal from
     * the state it produces), so the lock is guaranteed solvable.
     */
    public static LockModel solvable(long seed, int n, int scrambleMoves) {
        Random rnd = new Random(seed);
        Connection[][] connections = randomConnections(rnd, n);
        int[] state = new int[n]; // the goal
        LockModel m = new LockModel(n, state, connections, LockModel.MAX_OFFSET);
        for (int k = 0; k < scrambleMoves; k++) {
            int[] next = LockSolver.applyMove(m, state, rnd.nextInt(n), rnd.nextBoolean() ? 1 : -1);
            if (next != null) state = next;
        }
        return new LockModel(n, state, connections, LockModel.MAX_OFFSET);
    }

    /** Random per-mover drag rows: each other plate has a 1-in-4 chance, random Normal/Inverted. */
    public static Connection[][] randomConnections(Random rnd, int n) {
        Connection[][] connections = new Connection[n][];
        for (int p = 0; p < n; p++) {
            List<Connection> row = new ArrayList<>();
            for (int q = 0; q < n; q++) {
                if (q != p && rnd.nextInt(4) == 0) {
                    row.add(new Connection(q,
                            rnd.nextBoolean() ? Connection.Type.NORMAL : Connection.Type.INVERTED));
                }
            }
            connections[p] = row.toArray(new Connection[0]);
        }
        return connections;
    }
}
