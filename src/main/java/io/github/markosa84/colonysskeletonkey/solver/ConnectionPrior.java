package io.github.markosa84.colonysskeletonkey.solver;

/**
 * How likely a slide is to strain, before anything is known about the plate being slid.
 *
 * <p>Discovery sometimes has no safe move left and has to press a key and accept the risk. Which key
 * used to be chosen by a proxy - fewest other plates parked at an end, then toward centre - which
 * cannot tell a 37% gamble from a 44% one. This puts a number on it, measured from 203 real locks
 * (see {@code tools/LockStats.java}, which regenerates every constant here).
 *
 * <h2>What the corpus says, including what it rules out</h2>
 * Connections are all but structureless. Over 1657 connections in 5114 ordered plate pairs,
 * {@code P(p drags q) = 0.32} - and it is <b>flat</b>: 0.33 / 0.33 / 0.32 / 0.32 at distances 1 to 4,
 * and an average out-degree between 1.2 and 1.8 at every plate position of every 4-, 5- and 6-plate
 * lock. There is no "adjacent plates are linked more often", no "the front plate drags more". Do not
 * go looking again - and note the numbers barely moved when the corpus grew by half, and have moved
 * by hundredths across the two batches of locks since, which is what a structureless generator looks
 * like.
 *
 * <p>Two things are not flat, and they are what this class is built on:
 * <ul>
 *   <li><b>The total is budgeted.</b> A lock's connections cluster tightly around a mean that depends
 *       only on its plate count - 4 plates run 4..6, 5 run 5..10, 6 run 4..11, 7 run 8..10 - so the
 *       right question is not "is this pair linked" but "how many links are left to place, over how
 *       many unknown pairs", and the answer sharpens with every plate probed: a six-plate lock with
 *       four plates probed and eight connections already seen has about half a connection left to
 *       spread over ten unknown pairs, not the blind 0.28. What the prior leans on is that
 *       <b>mean</b>, and only that. Neither end of the observed range is a bound worth coding: "at
 *       least n" held over the first 124 locks and failed at 186 (a six-plate chest with four), and
 *       an upper ceiling of 10 held over 186 and failed at 190 (a six-plate chest with eleven).
 *       Both were sampling artefacts of the same kind as the recall key's supposed uniqueness, and
 *       neither guard rail could ever fire - the mean sits below every ceiling ever fitted, so it
 *       was always the binding term.</li>
 *   <li><b>Type is skewed.</b> 54.7% of connections are INVERTED. Small, but it is the whole
 *       difference between the two directions of the same slide (see {@link #strainRisk}).</li>
 * </ul>
 *
 * <p>The numbers for 4 and 7 plates rest on 16 and 6 locks respectively, so they are the softest
 * here - the 7-plate sample is thin enough that one plate position reads an out-degree of 0.33, which
 * is two connections over six locks rather than a fact about the front of a lock. That costs little,
 * since this only ever <i>orders</i> candidate moves. Nothing is ever concluded from a probability:
 * it picks which gamble to take, and the lock still says what happened.
 */
public final class ConnectionPrior {

    /** Mean connections per lock, indexed by plate count. Measured; index 0..3 are unused. */
    private static final double[] MEAN_EDGES = {0, 0, 0, 0, 5.56, 8.20, 8.47, 9.33};

    /** Measured 907/1657. The complement is the chance a connection is NORMAL. */
    private static final double P_INVERTED = 0.547;

    /** Chance an unknown plate pair is connected at all, given what this run has already probed. */
    private final double edgeProbability;

    private ConnectionPrior(double edgeProbability) {
        this.edgeProbability = edgeProbability;
    }

    /**
     * The prior as it stands part-way through discovery: the connections still unaccounted for,
     * spread evenly over the plate pairs still unknown. {@code conn[p]} is a probed row, or null
     * while plate {@code p} is unprobed.
     */
    public static ConnectionPrior from(int n, Connection[][] conn) {
        int seen = 0;
        int unknownSlots = 0;
        for (int p = 0; p < n; p++) {
            if (conn[p] == null) {
                unknownSlots += n - 1;
            } else {
                seen += conn[p].length;
            }
        }
        if (unknownSlots == 0) {
            return new ConnectionPrior(0);
        }
        double expected = n < MEAN_EDGES.length ? MEAN_EDGES[n] : MEAN_EDGES[MEAN_EDGES.length - 1];
        // Bounded by nothing beyond "not negative". Two guard rails have stood here, a floor of n and
        // a ceiling of MAX_EDGES, and both went the same way: the corpus grew and falsified the bound,
        // and neither had ever been able to fire, because the mean lies strictly inside both. A guard
        // rail that cannot trip is a claim, not a safeguard - so re-fit the mean, not the range.
        double remaining = Math.max(0, expected - seen);
        return new ConnectionPrior(clamp(remaining / unknownSlots, 0, 1));
    }

    /** The chance that {@code p} is connected to a given other plate, if nothing is known about it. */
    public double edgeProbability() {
        return edgeProbability;
    }

    /**
     * The chance that sliding {@code plate} by {@code dir} strains the pick, read off the offsets on
     * screen. A slide strains only by pushing some plate off the end of its track, so only plates
     * already parked at an end can do it - and each of those is fatal in exactly one of the two ways
     * it could be dragged:
     *
     * <ul>
     *   <li>a plate at {@code +MAX_OFFSET} is pushed off only if it moves further left, which is
     *       NORMAL when the slide is left and INVERTED when it is right;</li>
     *   <li>a plate at {@code -MAX_OFFSET} is the mirror image.</li>
     * </ul>
     *
     * <p>Because INVERTED is the commoner type, the two directions of the same slide are <b>not</b>
     * equally risky: on a six-plate lock with nothing probed yet and three plates parked at
     * {@code +3}, sliding left risks <b>0.34</b> and sliding right <b>0.40</b>. That difference is
     * invisible to a rule that counts plates at ends, and it is the point of this method.
     *
     * <p>{@code knownDrags} is whatever is already known for certain about this plate's row - which
     * may be nothing, or the partial row a strain deduced. A known connection settles its own term
     * outright rather than being guessed at: a certain strain returns 1, and a plate known to be
     * dragged the harmless way contributes nothing.
     */
    public double strainRisk(int[] state, int plate, int dir, Connection[] knownDrags) {
        if (Math.abs(state[plate] + dir) > LockModel.MAX_OFFSET) {
            return 1; // the plate would leave its own track; nothing else matters
        }
        double survives = 1;
        for (int q = 0; q < state.length; q++) {
            if (q == plate || Math.abs(state[q]) != LockModel.MAX_OFFSET) {
                continue; // interior plates cannot be pushed off by a single step
            }
            // The one way of dragging q that would push it off the end it sits at.
            boolean atLeftEnd = state[q] == LockModel.MAX_OFFSET;
            Connection.Type fatal = (atLeftEnd == (dir > 0))
                    ? Connection.Type.NORMAL : Connection.Type.INVERTED;
            Connection.Type known = typeOf(knownDrags, q);
            if (known == fatal) {
                return 1; // known to drag it off: this is not a gamble, it is a mistake
            }
            if (known == null) {
                survives *= 1 - edgeProbability * typeProbability(fatal);
            }
        }
        return 1 - survives;
    }

    /** How {@code row} drags plate {@code q}, or null if it does not - or is not known to. */
    private static Connection.Type typeOf(Connection[] row, int q) {
        if (row == null) {
            return null;
        }
        for (Connection c : row) {
            if (c.target() == q) {
                return c.type();
            }
        }
        return null;
    }

    private static double typeProbability(Connection.Type type) {
        return type == Connection.Type.INVERTED ? P_INVERTED : 1 - P_INVERTED;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
