package io.github.markosa84.colonysskeletonkey.session;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.Stdout;
import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Getting something back for a strain. A slide is refused for exactly one reason - it would drag some
 * plate off the end of its track - so the culprit is always among the plates parked at an end, dragged
 * the one way that would push it off. When only one plate qualifies there is nothing left to guess,
 * and the run keeps the connection.
 *
 * <p>Measured over the solve corpus, a third of real strains are that unambiguous. The pick was
 * spent either way; this is the difference between spending it for nothing and spending it for a fact.
 */
class StrainDeductionTest {

    /**
     * One plate at an end, and a gamble that hits it. Plate 1 turns out to drag plate 0, which is
     * parked at the left end - and since plate 0 is the only plate at either end, no other connection
     * could have refused the move.
     *
     * <p>The lie is what forces a gamble at all: without it plate 0, the one plate at an end, simply
     * steps toward centre and no strain ever happens. That is the ordinary case, and it is why this
     * situation needs constructing.
     */
    @Test
    void aStrainWithOnlyOnePlateAtAnEndSettlesTheConnectionOutright() {
        LockModel truth = LockModel.of(new int[] {3, 0, 0, 0},
                rows(row(), row(n(0)), row(), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.lieAtPlay = 1; // the one safe move is refused, so discovery has to gamble instead

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(log.contains("plate 1 drags plate 0 (normal)"),
                "the strain names the connection: " + log);
        assertTrue(log.contains("nothing else could have refused the move"), log);
        assertTrue(game.opened(), "and the run still opens the lock");
    }

    /**
     * The plate being slid does not count as a candidate - it is the one plate a strain proves
     * nothing about. So the classic deadlock, two plates dragging each other into opposite walls,
     * is unambiguous from either side: slide plate 1 and only plate 2 is out there to be pushed off.
     * Both deductions are correct, and both are the connection that made the lock unopenable.
     */
    @Test
    void theSlidPlateIsNotItsOwnSuspect() {
        LockModel truth = LockModel.of(new int[] {0, 3, -3, 0},
                rows(row(), row(n(2)), row(n(1)), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(game.strains > 0, "this lock is deadlocked and does strain: " + log);
        assertTrue(log.contains("plate 1 drags plate 2 (normal)"), log);
        assertTrue(log.contains("plate 2 drags plate 1 (normal)"), log);
    }

    /**
     * Two plates out at the ends, and the strain proves only that <i>one</i> of them is dragged -
     * a disjunction, not a connection. Nothing is recorded; the refusal memory already covers not
     * retrying the move.
     *
     * <p>Plates 1 and 2 deadlock each other at opposite ends, so they are gambled first (one suspect
     * each) and leave both ends occupied. Plate 0 is gambled next, with two plates out there, and
     * that strain has to settle nothing.
     */
    @Test
    void aStrainWithTwoSuspectsSettlesNothing() {
        LockModel truth = LockModel.of(new int[] {0, 3, -3, 0},
                rows(row(n(1)), row(n(2)), row(n(1)), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(log.contains("plate 0 will not slide"), "plate 0 must have strained: " + log);
        assertFalse(log.contains("that says what: plate 0"),
                "two plates could have refused it, so nothing is settled: " + log);
    }

    private static Connection n(int target) {
        return new Connection(target, NORMAL);
    }

    private static Connection[] row(Connection... connections) {
        return connections;
    }

    private static Connection[][] rows(Connection[]... rows) {
        return rows;
    }
}
