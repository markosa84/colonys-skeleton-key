package io.github.markosa84.colonysskeletonkey.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.Move;
import io.github.markosa84.colonysskeletonkey.solver.TestLocks;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The probing state machine, run against {@link FakeGame} - a simulated lock with hidden
 * connections and the game's real strain/break/reset rules. The session never sees the truth
 * model; everything it learns, it learns the way the live tool does: by moving a plate and
 * looking.
 */
class LockSessionTest {

    @Test
    void opensAnEasyLockWithoutASingleStrain() {
        // Every plate starts interior, so every probe is a Free move and no strain can happen.
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        new LockSession(game, game, game).run();

        assertTrue(game.opened());
        assertEquals(0, game.strains);
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(longs = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19})
    void opensSeededRandomSolvableLocks(long seed) {
        LockModel truth = TestLocks.solvable(seed, 4 + (int) (seed % 3), 14);
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        new LockSession(game, game, game).run();

        assertTrue(game.opened(), "seed " + seed + " left the lock at strains=" + game.strains);
    }

    /**
     * Two plates that drag each other into opposite walls can never move: the lock is genuinely
     * unopenable. The point of the test is the budget: one strain per direction ever tried, the
     * refusal memory blocks every retry, and the session reports itself stuck instead of feeding
     * the strain counter.
     */
    @Test
    void deadlockedLockCostsTwoStrainsAndStopsInsteadOfRetrying() {
        LockModel truth = LockModel.of(new int[] {0, 3, -3, 0},
                rows(row(), row(n(2)), row(n(1)), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        new LockSession(game, game, game).run();

        assertFalse(game.opened());
        assertEquals(2, game.strains, "one gamble per deadlocked plate, never retried");
        assertEquals(4, game.plays, "two gambles plus one probe of each free plate");
        assertEquals(3, game.endCursorCalls, "homed at start and re-homed after each strain");
    }

    /**
     * The old failure mode: at UNTRAINED a break resets the puzzle to the exact configuration a
     * gamble just failed in, and a session without memory re-plays the same gamble until every
     * pick in the inventory is gone. The refusal memory must survive the reset.
     */
    @Test
    void refusalMemorySurvivesABreakReset() {
        LockModel truth = LockModel.of(new int[] {0, 3, -3, 0},
                rows(row(), row(n(2)), row(n(1)), row()));
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED); // 2 strains per pick

        new LockSession(game, game, game).run();

        assertEquals(1, game.breaks, "the second strain breaks the pick");
        assertEquals(2, game.strains, "the reset must not cause the failed gambles to be retried");
        assertEquals(4, game.plays);
        assertFalse(game.opened());
    }

    /** A move the model calls legal that still strains discards the row and re-probes the plate. */
    @Test
    void aLyingObservationCorrectsTheModelInsteadOfDerailingTheRun() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.lieAtPlay = 5; // the first solving move, after the four probes, phantom-strains

        new LockSession(game, game, game).run();

        assertTrue(game.opened(), "one bad observation must cost a re-probe, not the run");
        assertEquals(0, game.strains, "the phantom strain never really happened");
        assertTrue(game.plays > 6, "the discarded row forces at least one extra probe");
    }

    /**
     * A probe that lands in a configuration where another plate's row is hidden must be undone -
     * a diff with a hidden plate could teach a silently wrong row - and retried later from a
     * different configuration. The whole lock still opens, without a single strain.
     */
    @Test
    void aProbeThatHidesARowIsUndoneAndRetriedFromElsewhere() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 1;
        game.hideWhen = s -> s[3] == 0; // plate 3 sitting centred covers plate 1's row

        new LockSession(game, game, game).run();

        assertTrue(game.opened(), "solving routes through the occluding configuration by filling"
                + " hidden entries from the learned model");
        assertEquals(0, game.strains, "occlusion is handled by undoing, never by strains");
    }

    /** A lock that is already occluded at rest gets nudged until every row is visible. */
    @Test
    void aSessionThatStartsHiddenNudgesItselfVisible() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, 0},
                rows(row(), row(n(0)), row(), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 1;
        game.hideWhen = s -> s[3] == 0; // hidden in the very configuration the session starts in

        new LockSession(game, game, game).run();

        assertTrue(game.opened());
        assertEquals(0, game.strains);
    }

    /**
     * A game where every move strains and breaks a pick must stop at the five-pick budget. The
     * budget counts <i>observed</i> breaks - the session is told nothing about the character's
     * skill, and needs to be told nothing.
     */
    @Test
    void givesUpAtTheFivePickBudget() {
        HostileGame game = new HostileGame(4);

        new LockSession(game, game, game).run();

        assertEquals(5, game.plays, "exactly one play per broken pick, then give up");
    }

    /**
     * The character's skill is never configured - it is read back out of a pick that broke. Four
     * strains to a pick is basic lockpicking, and that is what the run must say, having been told
     * only that the counter changed.
     */
    @Test
    void aBrokenPickTellsTheSessionWhatTheCharacterCanDo() {
        String log = capturingStdout(() -> {
            PickEater game = new PickEater(4, Skill.BASIC.mistakes());
            new LockSession(game, game, game).run();
        });

        assertTrue(log.contains("the pick broke after 4 strain(s) - that is basic lockpicking"), log);
    }

    /**
     * A pick carries its damage between locks, so the first one a run breaks may arrive already
     * worn and break early - at a strain count matching no level. The session must say so rather
     * than round it into the nearest skill: the whole point of observing is not to guess.
     */
    @Test
    void aWornPickIsReportedAsWornRatherThanGuessedIntoALevel() {
        String log = capturingStdout(() -> {
            PickEater game = new PickEater(4, 3); // breaks after 3 strains: no level does that
            new LockSession(game, game, game).run();
        });

        assertTrue(log.contains("the pick broke after 3 strain(s) - no level breaks on that many"),
                log);
    }

    private static String capturingStdout(Runnable body) {
        java.io.PrintStream real = System.out;
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buffer, true, java.nio.charset.StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(real);
        }
        return buffer.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Every move strains, and the lockpick counter reports a break on every
     * {@code strainsPerPick}-th one. The lock never moves, so the session runs out of moves to try;
     * what is under test is what it made of the breaks it saw on the way.
     */
    private static final class PickEater implements LockView, MoveExecutor, CursorKeys {
        private final int n;
        private final int strainsPerPick;
        private final int[] state;
        private int strains;
        private int cursor;

        PickEater(int n, int strainsPerPick) {
            this.n = n;
            this.strainsPerPick = strainsPerPick;
            this.state = new int[n];
            java.util.Arrays.fill(this.state, 1); // interior, so every probe looks safe to try
        }

        @Override
        public int detectPlateCount() {
            return n;
        }

        @Override
        public boolean[] readCentered(int n) {
            return new boolean[n];
        }

        @Override
        public void dumpFrame(String tag) {
        }

        @Override
        public Observation play(int n, int[] cur, Move move) {
            cursor = move.plate();
            boolean broke = ++strains % strainsPerPick == 0;
            return new Observation(Outcome.UNCHANGED, state.clone(), broke);
        }

        @Override
        public int[] settle(int n) {
            return state.clone();
        }

        @Override
        public void endCursor(int n) {
            cursor = n - 1;
        }

        @Override
        public int cursor() {
            return cursor;
        }
    }

    /** Every play strains and breaks a pick; the lock never changes. */
    private static final class HostileGame implements LockView, MoveExecutor, CursorKeys {
        private final int n;
        private final int[] state;
        int plays;
        private int cursor;

        HostileGame(int n) {
            this.n = n;
            this.state = new int[n];
            java.util.Arrays.fill(this.state, 1); // interior, so probes look promising
        }

        @Override
        public int detectPlateCount() {
            return n;
        }

        @Override
        public boolean[] readCentered(int n) {
            return new boolean[n];
        }

        @Override
        public void dumpFrame(String tag) {
        }

        @Override
        public Observation play(int n, int[] cur, Move move) {
            plays++;
            cursor = move.plate();
            return new Observation(Outcome.UNCHANGED, state.clone(), true);
        }

        @Override
        public int[] settle(int n) {
            return state.clone();
        }

        @Override
        public void endCursor(int n) {
            cursor = n - 1;
        }

        @Override
        public int cursor() {
            return cursor;
        }
    }

    private static Connection n(int target) {
        return new Connection(target, NORMAL);
    }

    private static Connection i(int target) {
        return new Connection(target, INVERTED);
    }

    private static Connection[] row(Connection... connections) {
        return connections;
    }

    private static Connection[][] rows(Connection[]... rows) {
        return rows;
    }
}
