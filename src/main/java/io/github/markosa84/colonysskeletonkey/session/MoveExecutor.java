package io.github.markosa84.colonysskeletonkey.session;

import io.github.markosa84.colonysskeletonkey.solver.Move;

/**
 * Executes one move against the real lock and reports what actually happened. The session reasons
 * only from these observations - never from the assumption that a key press worked.
 */
public interface MoveExecutor {

    /**
     * Selects the plate named by {@code move}, slides it once, and waits until the lock stops
     * moving.
     *
     * <p>{@code cur} is the settled state read before the key. The returned outcome is the truth,
     * whatever the caller expected: a move the model swore was legal can still come back
     * {@link Outcome#UNCHANGED}.
     *
     * @throws UnreadableFrame if the lock never settles into a readable state
     */
    Observation play(int n, int[] cur, Move move);

    /**
     * Reads a lock that should not be moving, tolerating the tail of an animation.
     *
     * @throws UnreadableFrame if the lock never settles into a readable state
     */
    int[] settle(int n);

    /** What the game did with a slide. */
    enum Outcome {
        /** The plate moved. {@code state} is where the lock ended up. */
        MOVED,
        /** Nothing moved: the move was rejected and the pick strained. */
        UNCHANGED,
        /** The pick broke; the game reset every plate to its start positions. */
        RESET,
    }

    /**
     * {@code state} is the settled lock after the attempt; entries may be
     * {@link io.github.markosa84.colonysskeletonkey.solver.LockModel#UNKNOWN} where a plate's
     * hole row is hidden in the settled configuration (an {@link Outcome#UNCHANGED} state never
     * is - a rejected move leaves the caller's fully-known pre-move state standing).
     * {@code pickBroke} is true when the remaining-lockpicks counter changed - the only signal a
     * break gives above skill level 0.
     */
    record Observation(Outcome outcome, int[] state, boolean pickBroke) {}

    /** Thrown when the lock never settles into a readable state, so no conclusion may be drawn. */
    final class UnreadableFrame extends RuntimeException {}
}
