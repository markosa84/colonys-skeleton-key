package io.github.markosa84.colonysskeletonkey.solver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Least-cost (Dijkstra) solver for the Gothic Remake lockpicking minigame.
 *
 * <p>The lock has several pieces, each sliding along a bounded track. The lock opens when
 * every piece is centered simultaneously. Each move selects one piece and slides it left or
 * right by one step. Pieces may be connected: moving a piece also moves the one-or-more pieces
 * it is directly connected to - each in the SAME direction (Normal) or the OPPOSITE direction
 * (Inverted). Connections do not cascade (only the moved piece's own connections fire), and
 * moves are atomic: if any affected piece would leave its track, the whole move is aborted and
 * the pick is strained.
 *
 * <p>The solver runs a least-cost search over (lock configuration, selected piece) states: moving
 * piece p costs {@code |p - selectedPiece|} navigation presses plus one slide, weighted by a
 * {@link Cost}. Because every edge cost is &gt;= 1, the first time the all-centered configuration
 * is dequeued it is reached by a least-cost path.
 *
 * <p>The lock is described by an immutable {@link LockModel}. The automation builds one at runtime
 * by probing the lock on screen, and solves from whatever configuration the lock is actually in.
 */
public final class LockSolver {

    private LockSolver() {}

    /** True when every piece is centered (offset 0). */
    public static boolean isGoal(int[] state) {
        for (int v : state) {
            if (v != 0) return false;
        }
        return true;
    }

    /**
     * Attempts to slide {@code piece} by {@code dir} (-1 = RIGHT, +1 = LEFT), dragging every
     * piece in {@code m.connections()[piece]}. Returns the resulting state, or {@code null} if
     * any affected piece would leave its track (an invalid, strain-inducing move).
     */
    public static int[] applyMove(LockModel m, int[] state, int piece, int dir) {
        int n = m.n();
        int[] delta = new int[n];
        delta[piece] += dir;
        for (Connection c : m.connections()[piece]) {
            delta[c.target()] += dir * c.type().sign();
        }
        int[] next = state.clone();
        for (int i = 0; i < n; i++) {
            if (delta[i] == 0) continue;
            int p = state[i] + delta[i];
            if (p < -m.maxOffset() || p > m.maxOffset()) {
                return null; // out of bounds -> the atomic move fails entirely
            }
            next[i] = p;
        }
        return next;
    }

    /** Mixed-radix encoding of a lock configuration into a unique non-negative long key. */
    public static long encode(LockModel m, int[] state) {
        long key = 0;
        int span = 2 * m.maxOffset() + 1;
        for (int i = 0; i < m.n(); i++) {
            key = key * span + (state[i] + m.maxOffset());
        }
        return key;
    }

    /** Records how a search state was reached, for path reconstruction. */
    private record Step(long prevKey, int piece, int dir) {}

    /** A node in the priority queue: cost so far, the configuration, its key, and the selected piece. */
    private record QNode(int cost, int[] config, long configKey, int cursor) {}

    /**
     * Least-cost search from the model's own start, with the selection on the last piece (where
     * the game parks it), minimizing keypresses.
     */
    public static List<Move> solve(LockModel m) {
        return solve(m, m.start(), m.n() - 1, Cost.KEYPRESS);
    }

    /**
     * Least-cost (Dijkstra) search from an arbitrary configuration and cursor. Returns the ordered
     * moves, or {@code null} if the centered state is unreachable with valid moves. Moving piece p
     * costs {@code |p - cursor| * cost.nav() + cost.slide()}.
     *
     * <p>{@code startConfig} need not be {@code m.start()}: the automation solves from wherever
     * probing left the lock, which saves a reset and the re-travel that follows it. That is always
     * possible when the lock is solvable at all - every probe move is legal, so its inverse is too,
     * and the search can route back through the start if nothing better exists.
     */
    public static List<Move> solve(LockModel m, int[] startConfig, int startCursor, Cost cost) {
        int n = m.n();
        int[] root = startConfig.clone();
        long startConfigKey = encode(m, root);
        long goalConfigKey = encode(m, new int[n]); // all zeros = centered

        Map<Long, Integer> dist = new HashMap<>(); // stateKey -> least cost found so far
        Map<Long, Step> prev = new HashMap<>();    // stateKey -> how it was reached
        PriorityQueue<QNode> pq = new PriorityQueue<>(Comparator.comparingInt(QNode::cost));

        long startStateKey = startConfigKey * n + startCursor;
        dist.put(startStateKey, 0);
        prev.put(startStateKey, new Step(-1, -1, 0)); // sentinel root
        pq.add(new QNode(0, root, startConfigKey, startCursor));

        while (!pq.isEmpty()) {
            QNode cur = pq.poll();
            long curStateKey = cur.configKey() * n + cur.cursor();
            if (cur.cost() > dist.getOrDefault(curStateKey, Integer.MAX_VALUE)) {
                continue; // stale priority-queue entry
            }
            if (cur.configKey() == goalConfigKey) {
                return reconstruct(prev, curStateKey); // first popped goal = least-cost path
            }
            for (int piece = 0; piece < n; piece++) {
                int navCost = Math.abs(piece - cur.cursor()); // W/S presses to select this piece
                for (int dir = -1; dir <= 1; dir += 2) {      // -1 = RIGHT, +1 = LEFT
                    int[] next = applyMove(m, cur.config(), piece, dir);
                    if (next == null) continue;               // invalid move (would strain the pick)
                    long nextConfigKey = encode(m, next);
                    long nextStateKey = nextConfigKey * n + piece;
                    int nextCost = cur.cost() + navCost * cost.nav() + cost.slide();
                    if (nextCost < dist.getOrDefault(nextStateKey, Integer.MAX_VALUE)) {
                        dist.put(nextStateKey, nextCost);
                        prev.put(nextStateKey, new Step(curStateKey, piece, dir));
                        pq.add(new QNode(nextCost, next, nextConfigKey, piece));
                    }
                }
            }
        }
        return null; // exhausted every reachable state without centering the lock
    }

    /** Walks the predecessor map back from the goal to build the move list in order. */
    private static List<Move> reconstruct(Map<Long, Step> prev, long goalKey) {
        List<Move> moves = new ArrayList<>();
        long key = goalKey;
        while (true) {
            Step s = prev.get(key);
            if (s == null || s.piece() == -1) break; // reached the sentinel root
            moves.add(new Move(s.piece(), s.dir()));
            key = s.prevKey();
        }
        Collections.reverse(moves);
        return moves;
    }
}
