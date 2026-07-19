package io.github.markosa84.colonysskeletonkey.solver;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * Pure graph math for the session's unsolvable-model recovery: given a learned {@link LockModel} the
 * {@link LockSolver} cannot open, which one plate's connection row could a single edit fix, and is a
 * repaired model solvable at all?
 *
 * <p>The game only ever hands out solvable locks, so a fully-probed model that will not open holds a
 * misread connection. The session uses {@link #singleEditRank} to <b>name</b> the likeliest culprit
 * (it then re-probes that plate to confirm, rather than trusting the edit). None of this touches
 * session state - it is reachability over configuration space, so it lives beside {@link LockSolver}
 * whose {@link LockSolver#applyMove}/{@link LockSolver#encode}/{@link LockSolver#isGoal} it is built
 * on, and is unit-tested directly by {@code ModelRepairTest}.
 */
public final class ModelRepair {

    private ModelRepair() {
    }

    /**
     * The most minimal single-edit to plate {@code p}'s row that makes the all-centered goal reachable
     * from {@code from}: 0 flips one connection's direction, 1 drops one, 2 flips them all (a mover
     * misread reverses the whole row's sense), 3 adds one absent drag. {@link Integer#MAX_VALUE} if no
     * single edit opens the lock.
     */
    public static int singleEditRank(LockModel m, int[] from, int p) {
        Connection[] row = m.connections()[p];
        if (anyReaches(m, from, p, flipEach(row))) {
            return 0;
        }
        if (anyReaches(m, from, p, dropEach(row))) {
            return 1;
        }
        if (row.length > 1 && reaches(m, from, p, flipAll(row))) {
            return 2;
        }
        if (anyReaches(m, from, p, addEach(m.n(), p, row))) {
            return 3;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean anyReaches(LockModel m, int[] from, int p, List<Connection[]> candidates) {
        for (Connection[] candidate : candidates) {
            if (reaches(m, from, p, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reaches(LockModel m, int[] from, int p, Connection[] row) {
        return reachesGoal(modelWith(m, p, row), from);
    }

    /** Every row that differs from {@code row} by flipping exactly one connection's direction. */
    static List<Connection[]> flipEach(Connection[] row) {
        List<Connection[]> out = new ArrayList<>();
        for (int j = 0; j < row.length; j++) {
            Connection[] c = row.clone();
            c[j] = new Connection(row[j].target(), flip(row[j].type()));
            out.add(c);
        }
        return out;
    }

    /** Every row that differs from {@code row} by dropping exactly one connection. */
    static List<Connection[]> dropEach(Connection[] row) {
        List<Connection[]> out = new ArrayList<>();
        for (int j = 0; j < row.length; j++) {
            Connection[] c = new Connection[row.length - 1];
            for (int k = 0, w = 0; k < row.length; k++) {
                if (k != j) {
                    c[w++] = row[k];
                }
            }
            out.add(c);
        }
        return out;
    }

    /** {@code row} with every connection's direction flipped - the shape of a misread mover frame. */
    static Connection[] flipAll(Connection[] row) {
        Connection[] c = new Connection[row.length];
        for (int j = 0; j < row.length; j++) {
            c[j] = new Connection(row[j].target(), flip(row[j].type()));
        }
        return c;
    }

    /** Every row that adds one absent drag target to {@code row}, as normal and as inverted. */
    static List<Connection[]> addEach(int n, int p, Connection[] row) {
        boolean[] present = new boolean[n];
        for (Connection c : row) {
            present[c.target()] = true;
        }
        List<Connection[]> out = new ArrayList<>();
        for (int q = 0; q < n; q++) {
            if (q == p || present[q]) {
                continue;
            }
            for (Connection.Type type : Connection.Type.values()) {
                Connection[] c = Arrays.copyOf(row, row.length + 1);
                c[row.length] = new Connection(q, type);
                out.add(c);
            }
        }
        return out;
    }

    private static Connection.Type flip(Connection.Type type) {
        return type == Connection.Type.NORMAL ? Connection.Type.INVERTED : Connection.Type.NORMAL;
    }

    /** {@code m} with plate {@code p}'s connection row replaced by {@code row}. */
    static LockModel modelWith(LockModel m, int p, Connection[] row) {
        Connection[][] known = m.connections().clone();
        known[p] = row;
        return new LockModel(m.n(), m.start(), known, m.maxOffset());
    }

    /**
     * True if the all-centered configuration is reachable from {@code from} under {@code m}. A plain
     * reachability flood over the configuration space (at most 7^7 states), which is all the suspect
     * search needs and far cheaper than a least-cost {@link LockSolver#solve}.
     */
    public static boolean reachesGoal(LockModel m, int[] from) {
        int span = 2 * m.maxOffset() + 1;
        int size = 1;
        for (int i = 0; i < m.n(); i++) {
            size *= span;
        }
        boolean[] seen = new boolean[size];
        Deque<int[]> queue = new ArrayDeque<>();
        seen[(int) LockSolver.encode(m, from)] = true;
        queue.add(from.clone());
        while (!queue.isEmpty()) {
            int[] state = queue.poll();
            if (LockSolver.isGoal(state)) {
                return true;
            }
            for (int p = 0; p < m.n(); p++) {
                for (int dir = -1; dir <= 1; dir += 2) {
                    int[] next = LockSolver.applyMove(m, state, p, dir);
                    if (next == null) {
                        continue;
                    }
                    int key = (int) LockSolver.encode(m, next);
                    if (!seen[key]) {
                        seen[key] = true;
                        queue.add(next);
                    }
                }
            }
        }
        return false;
    }
}
