package io.github.markosa84.colonysskeletonkey.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.markosa84.colonysskeletonkey.Stdout;
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
     * The 7-plate chest, exactly as it was probed off the live game (difficulty 4): plate 3 drags
     * two plates, plate 0 and plate 6 one each, and plates 1, 2 and 5 drag nothing. The reader was
     * verified against its 21 frames; this is the other half - that the session can actually open it.
     */
    @Test
    void opensTheLiveSevenPlateChest() {
        LockModel truth = LockModel.of(new int[] {0, -2, -3, 3, 3, 2, 3},
                rows(row(n(4)), row(), row(), row(n(1), n(4)), row(), row(), row(n(1))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(game.opened(), "left at " + Arrays.toString(game.state));
        assertEquals(0, game.strains, "every plate starts interior, so nothing needs a gamble");
    }

    /** No lock on the screen at all: say so, dump the frame, and type nothing. */
    @ParameterizedTest(name = "{0} plates is not a lock")
    @ValueSource(ints = {0, 3, 8})
    void aScreenWithoutALockIsReportedAndDumped(int plates) {
        BlindGame game = new BlindGame(plates);

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(log.contains("No 4-7 plate lock detected"), log);
        assertEquals(List.of("no-lock"), game.dumps);
        assertEquals(0, game.plays, "not one key may be sent at a lock that is not there");
    }

    /** The lock is there but nothing in it can be read: report and dump, never guess. */
    @Test
    void aLockThatCannotBeReadAtAllIsReportedAndDumped() {
        BlindGame game = new BlindGame(5);
        game.settleIsUnreadable = true;

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(log.contains("Could not read the lock before the first move"), log);
        assertEquals(List.of("unreadable"), game.dumps);
        assertEquals(0, game.plays);
    }

    /**
     * A row that stays hidden however the lock is nudged. The session may not learn a connection
     * from a diff containing an unread plate - that is the bug class the whole refusal machinery
     * exists to prevent - so it stops and says so, having spent no picks.
     */
    @Test
    void aRowThatNeverBecomesReadableStopsTheRunInsteadOfGuessing() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 1;
        game.hideWhen = s -> true; // hidden in every configuration there is

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened());
        assertEquals(0, game.strains, "an unreadable row costs time, never a pick");
        assertTrue(log.contains("Could not read the lock"), log);
        assertEquals(List.of("unreadable"), game.dumps);
    }

    /**
     * Some locks cannot be opened at all. Plates 0 and 1 drag each other the same way, so the
     * difference between their offsets never changes - starting two apart, they can never both be
     * centred. The session must learn that, say it, and stop; not feed the strain counter.
     */
    @Test
    void anUnopenableLockIsReportedOnceItHasBeenLearned() {
        LockModel truth = LockModel.of(new int[] {1, -1, 0, 0},
                rows(row(n(1)), row(n(0)), row(), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened());
        assertTrue(log.contains("No strain-free solution exists"), log);
    }

    /**
     * "No plate reads a non-zero offset" is <b>not</b> "the lock opened" - it is also true of a
     * frame nothing could be read in. Only the pin pops, which are the game's own exact signal, may
     * declare a lock open. Here the hole rows claim all-centred while a pin says otherwise: the
     * session must disbelieve the rows and go back for another look.
     */
    @Test
    void onlyThePinPopsMayDeclareTheLockOpen() {
        PinVetoGame game = new PinVetoGame(4, 1); // plate 1's pin has not popped, whatever the rows say

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(log.contains("Lock solved"), log);
        assertEquals(2, game.centeredReads, "the first all-zero reading was checked and rejected");
        assertEquals(0, game.plays, "and nothing was typed at a lock that was already open");
    }

    /** The move landed but the frame after it did not read: report it, dump it, learn nothing. */
    @Test
    void anUnreadableFrameMidRunIsReportedAndDumped() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.unreadableAtPlay = 3; // the lock stops reading while the connections are being probed

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened());
        assertTrue(log.contains("Could not read the lock mid-run"), log);
        assertEquals(List.of("unreadable"), game.dumps);
    }

    /**
     * The winning move lands and the minigame starts closing, so the next frame reads nothing. That
     * is a solved lock, not a failure - and the pins, which need no hole rows, say so.
     */
    @Test
    void aLockThatGoesUnreadableAsItOpensIsStillReportedSolved() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.unreadableOnceOpen = true;

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(game.opened());
        assertTrue(log.contains("Lock solved"), log);
        assertTrue(game.dumps.isEmpty(), "an open lock is not a failure to dump");
    }

    /**
     * A row that goes hidden mid-run and stays hidden through every nudge. The session may not
     * learn from a diff containing an unread plate, so it stops and tells the player how to break
     * the deadlock by hand.
     */
    @Test
    void aRowThatStaysHiddenAfterEveryNudgeStopsTheRun() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, -1},
                rows(row(), row(n(0)), row(), row(i(2))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 1;
        game.hideWhen = s -> game.plays >= 3; // from the third move on, plate 1's row never returns

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened());
        assertTrue(log.contains("Stuck: a plate's hole row stays hidden after every nudge"), log);
    }

    /**
     * A nudge is a move like any other, and it can strain: the plate it moves stays on its track,
     * but a plate it <i>drags</i> may be parked at an end. That is accepted - there is no way to
     * prove anything about a lock that cannot be fully seen - but it must be counted, and the
     * selection re-homed, exactly as for any other strain.
     */
    @Test
    void aNudgeThatStrainsIsCountedLikeAnyOtherStrain() {
        // Plate 3 drags plate 1, which sits at the +3 end: nudging plate 3 towards centre (+1)
        // would push plate 1 off its track.
        LockModel truth = LockModel.of(new int[] {1, 3, 0, -2},
                rows(row(), row(), row(), row(n(1))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 0;
        game.hideWhen = s -> true; // nothing will ever uncover plate 0's row

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertEquals(1, game.strains, "the nudge dragged a plate off its end");
        assertTrue(game.endCursorCalls > 1, "a strain always re-homes the selection");
        assertTrue(log.contains("Could not read the lock"), log);
    }

    /** And a nudge that breaks a pick is a broken pick: counted, waited out, carried on from. */
    @Test
    void aNudgeThatBreaksAPickIsBookedAsABreak() {
        // Plate 3 drags plate 1 (parked at +3) and plate 2 (parked at -3): both directions strain.
        LockModel truth = LockModel.of(new int[] {1, 3, -3, -2},
                rows(row(), row(), row(), row(n(1), n(2))));
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED); // two strains to a pick
        game.hiddenPlate = 0;
        game.hideWhen = s -> true;

        Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertEquals(2, game.strains, "one strain per direction of the blocked nudge");
        assertEquals(1, game.breaks, "the second strain broke the pick");
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
        String log = Stdout.capturing(() -> {
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
        String log = Stdout.capturing(() -> {
            PickEater game = new PickEater(4, 3); // breaks after 3 strains: no level does that
            new LockSession(game, game, game).run();
        });

        assertTrue(log.contains("the pick broke after 3 strain(s) - no level breaks on that many"),
                log);
    }

    /**
     * A lock whose hole rows read all-centred while one pin has not popped. The rows are lying (or
     * the frame is), and the pop is the truth; on the second look the pin agrees and the lock is
     * open. Nothing here can be moved, so any key the session sends is a bug.
     */
    private static final class PinVetoGame implements LockView, MoveExecutor, CursorKeys {
        private final int n;
        private final int unpopped;
        int centeredReads;
        int plays;

        PinVetoGame(int n, int unpopped) {
            this.n = n;
            this.unpopped = unpopped;
        }

        @Override
        public int detectPlateCount() {
            return n;
        }

        @Override
        public boolean[] readCentered(int n) {
            boolean[] popped = new boolean[n];
            java.util.Arrays.fill(popped, true);
            if (centeredReads++ == 0) {
                popped[unpopped] = false; // the first look: this plate is not centred at all
            }
            return popped;
        }

        @Override
        public void dumpFrame(String tag) {
        }

        @Override
        public Observation play(int n, int[] cur, Move move) {
            plays++;
            return new Observation(Outcome.UNCHANGED, cur.clone(), false);
        }

        @Override
        public int[] settle(int n) {
            return new int[n]; // the hole rows say every plate is centred
        }

        @Override
        public void endCursor(int n) {
        }

        @Override
        public int cursor() {
            return 0;
        }
    }

    /**
     * A lock the reader cannot make sense of: it reports whatever plate count it was given and
     * refuses to read a state. Nothing here is playable, which is the point - the session must
     * bail out before it types anything.
     */
    private static final class BlindGame implements LockView, MoveExecutor, CursorKeys {
        private final int plates;
        boolean settleIsUnreadable;
        int plays;
        final List<String> dumps = new ArrayList<>();

        BlindGame(int plates) {
            this.plates = plates;
        }

        @Override
        public int detectPlateCount() {
            return plates;
        }

        @Override
        public boolean[] readCentered(int n) {
            return new boolean[n];
        }

        @Override
        public void dumpFrame(String tag) {
            dumps.add(tag);
        }

        @Override
        public Observation play(int n, int[] cur, Move move) {
            plays++;
            return new Observation(Outcome.UNCHANGED, cur.clone(), false);
        }

        @Override
        public int[] settle(int n) {
            if (settleIsUnreadable) {
                throw new UnreadableFrame();
            }
            return new int[n];
        }

        @Override
        public void endCursor(int n) {
        }

        @Override
        public int cursor() {
            return 0;
        }
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
