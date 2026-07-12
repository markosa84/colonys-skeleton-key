package io.github.markosa84.colonysskeletonkey.solver;

import org.junit.jupiter.api.Test;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The domain vocabulary: the lock, the drag links, and the two cost weightings. Small types, but
 * every one of them encodes a rule of the game that the rest of the tool takes for granted.
 */
class LockModelTest {

    @Test
    void theTrackIsTheGamesOwn() {
        assertEquals(3, LockModel.MAX_OFFSET, "7 holes per plate: offsets -3..+3");
        assertEquals(4, LockModel.MIN_PLATES);
        assertEquals(7, LockModel.MAX_PLATES);
    }

    @Test
    void ofInfersThePlateCountAndUsesTheStandardTrack() {
        LockModel m = LockModel.of(new int[] {1, -1, 0, 2},
                new Connection[][] {{}, {new Connection(0, NORMAL)}, {}, {}});

        assertEquals(4, m.n());
        assertEquals(LockModel.MAX_OFFSET, m.maxOffset());
        assertArrayEquals(new int[] {1, -1, 0, 2}, m.start());
    }

    /**
     * UNKNOWN is part of the state vocabulary because observations legitimately contain it - a plate
     * mid-slide, or a row the reader refuses to guess about. The solver never accepts one, so
     * everything that hands a state to the solver checks this first.
     */
    @Test
    void aStateIsCompleteOnlyWhenEveryPlateWasActuallyObserved() {
        assertTrue(LockModel.isComplete(new int[] {0, -3, 3, 1}));
        assertTrue(LockModel.isComplete(new int[] {}), "nothing to be unsure about");
        assertFalse(LockModel.isComplete(new int[] {0, LockModel.UNKNOWN, 3, 1}));
        assertFalse(LockModel.isComplete(new int[] {LockModel.UNKNOWN}));
    }

    /** UNKNOWN must be a value no real offset can ever collide with. */
    @Test
    void unknownIsNotAReachableOffset() {
        assertTrue(LockModel.UNKNOWN < -LockModel.MAX_OFFSET);
        assertEquals(Integer.MIN_VALUE, LockModel.UNKNOWN);
    }

    @Test
    void aDragLinkFollowsTheMoverOrOpposesIt() {
        assertEquals(+1, NORMAL.sign(), "the dragged plate slides the same way as the mover");
        assertEquals(-1, INVERTED.sign(), "and the opposite way when the link is inverted");

        Connection c = new Connection(4, INVERTED);
        assertEquals(4, c.target());
        assertEquals(INVERTED, c.type());
    }

    /**
     * Both weightings must stay positive, or the search stops being a shortest-path problem. The
     * wall-clock numbers are the measured ones: a slide is ~300ms of animation to watch out, a
     * selection change ~10ms of key.
     */
    @Test
    void bothCostWeightingsArePositiveAndSayWhatTheyMeasure() {
        assertEquals(new Cost(1, 1), Cost.KEYPRESS, "every press costs the same: fewest keys");
        assertEquals(new Cost(300, 10), Cost.WALLCLOCK, "milliseconds, measured against the game");

        for (Cost cost : new Cost[] {Cost.KEYPRESS, Cost.WALLCLOCK}) {
            assertTrue(cost.slide() > 0 && cost.nav() > 0, cost.toString());
        }
    }

    /** dir +1 = LEFT = A; dir -1 = RIGHT = D. The whole tool is built on this sign convention. */
    @Test
    void aMoveIsAPlateAndADirection() {
        Move left = new Move(2, +1);

        assertEquals(2, left.plate());
        assertEquals(+1, left.dir());
        assertEquals(new Move(2, +1), left);
    }
}
