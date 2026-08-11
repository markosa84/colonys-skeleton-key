package io.github.markosa84.colonysskeletonkey.solver;

import org.junit.jupiter.api.Test;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The containment predicate the recall path turns on.
 *
 * <p>It is three lines, and it decides something the tool cannot afford to get wrong. The game's
 * skill mechanic only ever <b>removes</b> a plate connection, so a lock is always a subset of its
 * untrained self. A remembered model that is too full is therefore safe - a move legal under a
 * superset is legal under the truth - while one that is <i>missing</i> a connection the lock has can
 * strain the pick, and cannot be this chest at any level. {@code LockSession} reads the difference
 * off this method, and {@code LockCatalog} decides with it whether one key names one chest or two.
 */
class ConnectionTest {

    private static final Connection[] TWO = {new Connection(1, NORMAL), new Connection(3, INVERTED)};

    @Test
    void aRowContainsItselfAndAnythingTrimmedOutOfIt() {
        assertTrue(Connection.rowContains(TWO, TWO), "a row contains itself");
        assertTrue(Connection.rowContains(TWO, new Connection[] {new Connection(3, INVERTED)}),
                "one connection removed is what training does");
        assertTrue(Connection.rowContains(TWO, new Connection[0]), "and so is both of them");
        assertTrue(Connection.rowContains(new Connection[0], new Connection[0]));
    }

    @Test
    void aRowDoesNotContainAConnectionItLacksOrOneOfTheOtherType() {
        assertFalse(Connection.rowContains(TWO, new Connection[] {new Connection(2, NORMAL)}),
                "a connection to a plate this row never drags");
        assertFalse(Connection.rowContains(TWO, new Connection[] {new Connection(1, INVERTED)}),
                "the same plate dragged the other way is a different connection, not a subset");
        assertFalse(Connection.rowContains(new Connection[0], TWO));
    }

    /** Neither argument is ever null in the tool; if one ever is, it is not a containment. */
    @Test
    void aNullRowContainsNothingAndIsContainedByNothing() {
        assertFalse(Connection.rowContains(null, TWO));
        assertFalse(Connection.rowContains(TWO, null));
    }

    @Test
    void aModelContainsAnotherWhenEveryRowDoes() {
        Connection[][] untrained = {TWO, {new Connection(0, NORMAL)}, {}};
        Connection[][] trained = {{new Connection(3, INVERTED)}, {new Connection(0, NORMAL)}, {}};

        assertTrue(Connection.contains(untrained, trained), "trained away one connection");
        assertTrue(Connection.contains(untrained, untrained));
        assertFalse(Connection.contains(trained, untrained), "and not the other way round");
    }

    /**
     * Two chests that share a starting configuration: neither model contains the other, which is
     * exactly what says they are two locks rather than one lock seen at two skill levels.
     */
    @Test
    void neitherContainsTheOtherWhenTheyAreSimplyDifferentLocks() {
        Connection[][] one = {{new Connection(1, NORMAL)}, {}, {}};
        Connection[][] other = {{}, {new Connection(2, INVERTED)}, {}};

        assertFalse(Connection.contains(one, other));
        assertFalse(Connection.contains(other, one));
    }

    @Test
    void aModelOfADifferentSizeIsNotAContainmentQuestionAtAll() {
        Connection[][] four = {{}, {}, {}, {}};
        Connection[][] three = {{}, {}, {}};

        assertFalse(Connection.contains(four, three), "a four-plate lock is not a five-plate one");
        assertFalse(Connection.contains(null, three));
        assertFalse(Connection.contains(three, null));
    }
}
