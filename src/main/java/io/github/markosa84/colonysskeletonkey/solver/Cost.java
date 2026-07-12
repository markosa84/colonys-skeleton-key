package io.github.markosa84.colonysskeletonkey.solver;

/**
 * What one slide and one navigation press cost, in whatever unit the caller is minimizing.
 * Both must be positive or the search is no longer a shortest-path problem.
 */
public record Cost(int slide, int nav) {

    /** Every press costs the same: minimizes the number of keys pressed. */
    public static final Cost KEYPRESS = new Cost(1, 1);

    /**
     * Milliseconds, measured against the live game. A slide must be watched to completion before
     * the next move can be chosen: the plates move from ~110ms to ~210ms after the key, and the
     * reader confirms a stable state by ~270ms. A W/S press changes the selection instantly and is
     * never queued behind an animation, so it costs only its own hold and gap.
     *
     * <p>In practice it never disagrees with {@link #KEYPRESS}: a redundant slide can never buy
     * back navigation, so the fewest-keys plan already uses the fewest slides. Measured over the
     * live 5-plate lock and 200 random starts of a 6-plate example, the two never once chose
     * different plans. It is kept because it is the honest cost for the automation, not a speed-up.
     */
    public static final Cost WALLCLOCK = new Cost(300, 10);
}
