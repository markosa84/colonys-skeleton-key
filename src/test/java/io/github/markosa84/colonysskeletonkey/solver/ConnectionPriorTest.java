package io.github.markosa84.colonysskeletonkey.solver;

import org.junit.jupiter.api.Test;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The measured odds behind a gamble. Nothing here is ever <i>concluded</i> - the numbers only order
 * candidate moves, and the lock still says what actually happened - so what is pinned is that the
 * ordering is the right way round and that the arithmetic cannot wander outside 0..1.
 */
class ConnectionPriorTest {

    private static final Connection[][] SIX_UNPROBED = new Connection[6][];

    @Test
    void nothingAtAnEndCannotStrain() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);

        assertEquals(0, prior.strainRisk(new int[] {0, 1, -1, 2, -2, 0}, 0, +1, null), 1e-9,
                "a one-step drag of an interior plate always stays on the track");
    }

    @Test
    void aPlateThatWouldLeaveItsOwnTrackIsACertainStrain() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);

        assertEquals(1, prior.strainRisk(new int[] {3, 0, 0, 0, 0, 0}, 0, +1, null), 1e-9);
    }

    /**
     * The asymmetry the old "count the plates at the ends" rule could not see. INVERTED is the
     * commoner connection, so plates parked at the left end are the safer ones to slide away from.
     */
    @Test
    void theTwoDirectionsOfTheSameSlideAreNotEquallyRisky() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);
        int[] threeAtTheLeftEnd = {0, 3, 3, 3, 0, 0};

        double left = prior.strainRisk(threeAtTheLeftEnd, 0, +1, null);
        double right = prior.strainRisk(threeAtTheLeftEnd, 0, -1, null);

        assertTrue(left < right, "left " + left + " should beat right " + right);
        // Pinned to the figures the class javadoc and the docs quote, so neither can rot silently -
        // which is exactly what this keeps catching. The left-hand figure went 0.34 -> 0.33 when the
        // constants were re-measured over 186 locks, held at 190, and came back to 0.34 at 203; the
        // right-hand one has never moved off 0.40. Re-measure both if you touch MEAN_EDGES or
        // P_INVERTED, and carry the new numbers into the javadoc and CLAUDE.md with them.
        assertEquals(0.34, left, 0.005);
        assertEquals(0.40, right, 0.005);
    }

    @Test
    void riskRisesWithEveryFurtherPlateParkedAtAnEnd() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);

        double one = prior.strainRisk(new int[] {0, 3, 0, 0, 0, 0}, 0, +1, null);
        double two = prior.strainRisk(new int[] {0, 3, 3, 0, 0, 0}, 0, +1, null);
        double three = prior.strainRisk(new int[] {0, 3, 3, 3, 0, 0}, 0, +1, null);

        assertTrue(one < two && two < three, one + " < " + two + " < " + three);
        assertTrue(three < 1, "three plates at an end is still only a gamble: " + three);
    }

    /** A connection already known settles its own term - it is not re-guessed at. */
    @Test
    void aKnownFatalDragIsACertainty() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);
        Connection[] knownToDragPlateOne = {new Connection(1, NORMAL)};

        assertEquals(1, prior.strainRisk(new int[] {0, 3, 0, 0, 0, 0}, 0, +1, knownToDragPlateOne),
                1e-9, "dragging a plate at the left end further left is not a gamble");
    }

    @Test
    void aKnownHarmlessDragContributesNothing() {
        ConnectionPrior prior = ConnectionPrior.from(6, SIX_UNPROBED);
        Connection[] dragsItTheOtherWay = {new Connection(1, INVERTED)};

        assertEquals(0, prior.strainRisk(new int[] {0, 3, 0, 0, 0, 0}, 0, +1, dragsItTheOtherWay),
                1e-9, "plate 1 moves off the end only if dragged NORMAL, and it is not");
    }

    /**
     * The one thing about the corpus that is not flat: the number of connections in a lock is
     * budgeted, so every probed row makes the rest of the lock more predictable rather than leaving
     * a fixed 0.32 hanging over every unknown pair.
     */
    @Test
    void theOddsSharpenAsPlatesAreProbed() {
        double blind = ConnectionPrior.from(6, SIX_UNPROBED).edgeProbability();

        Connection[][] mostlyKnown = new Connection[6][];
        mostlyKnown[0] = new Connection[] {new Connection(1, NORMAL), new Connection(2, INVERTED)};
        mostlyKnown[1] = new Connection[] {new Connection(3, NORMAL), new Connection(4, INVERTED)};
        mostlyKnown[2] = new Connection[] {new Connection(5, NORMAL), new Connection(0, INVERTED)};
        mostlyKnown[3] = new Connection[] {new Connection(1, NORMAL), new Connection(2, NORMAL)};
        double afterEightSeen = ConnectionPrior.from(6, mostlyKnown).edgeProbability();

        assertTrue(afterEightSeen < blind,
                "eight of the ~8.5 a six-plate lock averages are already placed: " + afterEightSeen
                        + " should be below the blind " + blind);
        assertTrue(blind > 0.2 && blind < 0.45, "the blind prior is the corpus rate: " + blind);
    }

    /**
     * A lock whose probed rows already hold more connections than its size averages leaves nothing to
     * expect - and expects a negative number of connections nowhere. Real locks do run over the mean:
     * a six-plate chest with eleven is in the corpus, against a mean of 8.5.
     */
    @Test
    void aLockAlreadyPastTheCorpusMeanExpectsNoMore() {
        Connection[][] pastTheMean = new Connection[6][];
        for (int p = 0; p < 5; p++) {
            pastTheMean[p] = new Connection[] {
                new Connection((p + 1) % 6, NORMAL), new Connection((p + 2) % 6, NORMAL)
            };
        }

        assertEquals(0, ConnectionPrior.from(6, pastTheMean).edgeProbability(), 1e-9);
    }

    @Test
    void aFullyProbedLockHasNothingLeftToGuessAbout() {
        Connection[][] all = new Connection[4][];
        for (int p = 0; p < 4; p++) {
            all[p] = new Connection[0];
        }

        assertEquals(0, ConnectionPrior.from(4, all).edgeProbability(), 1e-9);
    }

    /** Whatever has been probed, a probability stays a probability. */
    @Test
    void theOddsNeverLeaveTheUnitInterval() {
        for (int n = LockModel.MIN_PLATES; n <= LockModel.MAX_PLATES; n++) {
            for (int probed = 0; probed <= n; probed++) {
                Connection[][] conn = new Connection[n][];
                for (int p = 0; p < probed; p++) {
                    Connection[] row = new Connection[n - 1];
                    for (int j = 0, q = 0; q < n; q++) {
                        if (q != p) {
                            row[j++] = new Connection(q, NORMAL);
                        }
                    }
                    conn[p] = row; // every plate dragging every other: far past anything real
                }
                double p = ConnectionPrior.from(n, conn).edgeProbability();
                assertTrue(p >= 0 && p <= 1, "n=" + n + " probed=" + probed + " gave " + p);
            }
        }
    }
}
