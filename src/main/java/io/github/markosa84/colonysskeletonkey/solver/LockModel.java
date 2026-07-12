package io.github.markosa84.colonysskeletonkey.solver;

/**
 * Immutable description of a lock: piece count, starting offsets, per-mover connection rows, and
 * the track radius. {@code connections[p]} lists every piece that moving piece {@code p} drags
 * along; use an empty row for a piece that drags nothing.
 *
 * <p>Offset convention: {@code 0} is centered, <b>positive = LEFT</b> of center, negative = RIGHT;
 * every piece slides within {@code [-maxOffset, +maxOffset]}.
 */
public record LockModel(int n, int[] start, Connection[][] connections, int maxOffset) {

    /** Track radius of every lock in the game: 7 holes per plate, offsets -3..+3. */
    public static final int MAX_OFFSET = 3;

    /** Plate counts the game uses. */
    public static final int MIN_PLATES = 4;
    public static final int MAX_PLATES = 7;

    /**
     * Sentinel for a state-vector entry that could not be observed: a plate mid-slide, or a row
     * the reader could not resolve and refuses to guess about. It is part of
     * the state vocabulary because observations legitimately contain it; the solver itself never
     * accepts a state with unknowns.
     */
    public static final int UNKNOWN = Integer.MIN_VALUE;

    /** True if every entry of the state vector was actually observed (no {@link #UNKNOWN}). */
    public static boolean isComplete(int[] state) {
        for (int v : state) {
            if (v == UNKNOWN) return false;
        }
        return true;
    }

    /** Builds a model over the game's standard track, inferring the piece count. */
    public static LockModel of(int[] start, Connection[][] connections) {
        return new LockModel(start.length, start, connections, MAX_OFFSET);
    }
}
