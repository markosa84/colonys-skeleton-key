package io.github.markosa84.colonysskeletonkey.session;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.Stdout;
import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.Cost;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.Move;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a lock the tool has opened before. Discovery carries all of a run's strain risk and, over
 * the whole corpus, 14% of its slides - so recognising a chest is the biggest saving available, but
 * only if a remembered model is never allowed to become an assumption. Both halves are pinned here: a
 * hit costs <b>exactly the solution</b> and nothing more, and a hit that turns out to be wrong still
 * opens the lock, because every move is checked against what the plates actually did.
 *
 * <p>The interesting case is not a stale memory but a <b>trained character</b>. Trained lockpicking
 * removes one plate connection and Master removes another, so the catalogue - recorded untrained -
 * remembers <i>more</i> connections than a skilled player's lock has. That over-full model mispredicts
 * where plates land, and cannot strain: a move legal when more plates are dragged is legal when fewer
 * are.
 */
class LockRecallTest {

    /** Lock 001 of the bundled catalogue - a real six-plate chest, connections and all. */
    private static final int[] START = {1, 0, -1, -3, 0, 0};

    private static Connection[][] realLock() {
        return rows(
                row(),
                row(n(3), i(4)),
                row(n(4), i(5)),
                row(i(1)),
                row(i(0), i(2), i(3)),
                row(i(4)));
    }

    @Test
    void aRecognisedLockCostsExactlyItsSolutionAndNotOneMoveMore() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED);
        int solution = LockSolver.solve(truth, START, truth.n() - 1, Cost.WALLCLOCK).size();

        Stdout.capturing(() -> new LockSession(game, game, game, remembering(truth)).run());

        assertTrue(game.opened());
        assertEquals(0, game.strains, "a remembered model is never gambled on");
        assertEquals(solution, game.plays, "no probing at all: the run is the solution");
    }

    /**
     * Recall can never cost <i>more</i> than probing. It does not always cost less either - on this
     * particular chest every discovery move happens to lie on the solution path, so both runs take 19
     * - which is exactly why the saving is measured over the whole corpus in {@code LockCorpusTest}
     * rather than argued from one lock here.
     */
    @Test
    void recognisingALockNeverCostsMoreMovesThanProbingIt() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame probing = new FakeGame(truth, Skill.UNTRAINED);
        FakeGame recalling = new FakeGame(truth, Skill.UNTRAINED);

        Stdout.capturing(() -> new LockSession(probing, probing, probing).run());
        Stdout.capturing(() ->
                new LockSession(recalling, recalling, recalling, remembering(truth)).run());

        assertTrue(recalling.plays <= probing.plays,
                "recall " + recalling.plays + " plays vs probing " + probing.plays);
        assertTrue(recalling.strains <= probing.strains);
    }

    @Test
    void theRunSaysItRecognisedTheLock() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED);

        String log = Stdout.capturing(() -> new LockSession(game, game, game, remembering(truth)).run());

        assertTrue(log.contains("opened this lock before"), log);
        assertTrue(log.contains("nothing to probe"), log);
    }

    /**
     * The trained-character case, and the reason recall is safe rather than merely lucky. The memory
     * holds one connection the lock no longer has; the plan it produces is wrong, and not one move of
     * it can strain.
     */
    @Test
    void aMemoryWithOneConnectionTooManyStillOpensTheLockWithoutStraining() {
        LockModel truth = LockModel.of(START, trainedAwayOneConnection(realLock()));
        FakeGame game = new FakeGame(truth, Skill.BASIC);

        String log = Stdout.capturing(() ->
                new LockSession(game, game, game, remembering(START, realLock())).run());

        assertTrue(game.opened(), "an over-full memory costs moves, never the lock");
        assertEquals(0, game.strains, "a superset of the truth cannot make an illegal move look legal");
        assertTrue(log.contains("one connection fewer"), "the correction should name the cause: " + log);
    }

    /** Two connections removed - a master character against an untrained memory. Still strain-free. */
    @Test
    void aMemoryWithTwoConnectionsTooManyStillOpensTheLockWithoutStraining() {
        LockModel truth = LockModel.of(START,
                trainedAwayOneConnection(trainedAwayOneConnection(realLock())));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        Stdout.capturing(() -> new LockSession(game, game, game, remembering(START, realLock())).run());

        assertTrue(game.opened());
        assertEquals(0, game.strains);
    }

    /**
     * What training does to a lock: one plate connection gone. The game only ever hands out locks that
     * open, so the connection removed here is one whose removal leaves this one openable - picking
     * blindly can produce a puzzle with no solution, which is not a lock the tool would ever meet.
     */
    private static Connection[][] trainedAwayOneConnection(Connection[][] model) {
        for (int p = 0; p < model.length; p++) {
            for (int k = 0; k < model[p].length; k++) {
                Connection[][] fewer = model.clone();
                List<Connection> kept = new ArrayList<>(List.of(model[p]));
                kept.remove(k);
                fewer[p] = kept.toArray(new Connection[0]);
                LockModel candidate = LockModel.of(START, fewer);
                if (LockSolver.solve(candidate, START, model.length - 1, Cost.WALLCLOCK) != null) {
                    return fewer;
                }
            }
        }
        throw new IllegalStateException("no single connection can be removed and leave it openable");
    }

    /**
     * A memory that cannot open the lock from where the lock actually is belongs to another chest, or
     * has rotted. It is dropped before a key is pressed, and the run probes as if nothing were known.
     */
    @Test
    void aMemoryThatCannotOpenTheLockIsIgnoredEntirely() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED);
        // Plates 0 and 1 chained into opposite walls: nothing can ever move under this model.
        Connection[][] deadlocked = rows(row(i(1)), row(i(0)), row(), row(), row(), row());

        String log = Stdout.capturing(() ->
                new LockSession(game, game, game, remembering(START, deadlocked)).run());

        assertTrue(game.opened(), "the lock still opens the ordinary way");
        assertTrue(log.contains("Learning the connections"), log);
        assertTrue(!log.contains("opened this lock before"), "nothing should claim recognition: " + log);
    }

    /**
     * A memory that is simply wrong - not a subset, not a superset - can strain, and must still
     * finish. The verification drops the bad row on the first surprise and discovery relearns it.
     */
    @Test
    void aWrongMemoryIsCorrectedRatherThanBelieved() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        Connection[][] wrong = realLock();
        wrong[5] = row(n(0), n(2)); // really 4 inverted

        Stdout.capturing(() -> new LockSession(game, game, game, remembering(START, wrong)).run());

        assertTrue(game.opened(), "a wrong memory costs moves, never the run");
        assertNotEquals(0, game.plays);
    }

    // --- two chests, one key -----------------------------------------------------------------------

    /**
     * The live failure of 2026-08-11, in full. A four-plate chest showing {@code [3, 3, -3, -2]} was
     * recognised as the bundled catalogue's <b>lock 014</b>, a different four-plate chest that starts
     * at exactly the same offsets. The memory says plate 2 drags nothing; the real chest drags plate 0
     * with it, and plate 0 was parked at the end of its track - so the first move of the plan strained.
     *
     * <p>The old code cleared plate 2's row and left the other three standing. They were fiction about
     * another lock, and {@code planUnblock} and {@code repositionForFreshGamble} search only over
     * plates whose rows are "known": under that fiction no move at all is legal, so discovery found
     * nothing, and a lock the reader had read perfectly was reported as <i>"the lock I read is not the
     * lock on screen … a bug in this tool"</i>. Three F8 presses, several strains and a lockpick went
     * that way.
     *
     * <p>The strain is what settles it, and it settles it as a proof rather than a guess: training
     * only ever <b>removes</b> a plate connection, so a memory of this chest could only be a superset
     * of the truth, and a move legal under a superset is legal under the truth. A memory that strains
     * is therefore not this chest at any skill level.
     */
    @Test
    void aMemoryOfAnotherChestWithTheSameOffsetsIsDroppedWholeAndTheLockStillOpens() {
        LockModel truth = LockModel.of(COLLIDING_START, theChestOnScreen());
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED);

        String log = Stdout.capturing(() -> new LockSession(game, game, game,
                remembering(COLLIDING_START, lock014())).run());

        assertTrue(game.opened(), "the lock opens: " + log);
        assertEquals(1, game.strains,
                "one strain to find the memory out, and none after it: " + log);
        assertTrue(log.contains("it is a different one that starts at the same offsets"),
                "the run must name the collision, not blame the reader: " + log);
        assertTrue(game.dumps.isEmpty(), "nothing to report: " + game.dumps);
    }

    /**
     * The same collision with a part-worn pick, which is how it actually arrived: the pick broke on
     * the very first strain. The break used to return before the model was corrected, so plate 2's
     * disproved row survived, the next pass re-solved to the identical plan, and the identical move
     * was sent again for a second strain. A pick breaking is not a reason to believe a row the lock
     * has just disproved.
     */
    @Test
    void aStrainThatBreaksThePickStillDisprovesTheRowAndTheMoveIsNeverRepeated() {
        LockModel truth = LockModel.of(COLLIDING_START, theChestOnScreen());
        FakeGame game = new FakeGame(truth, Skill.UNTRAINED);
        game.wornBy = 1; // untrained breaks on the second strain, so this one breaks on the first

        String log = Stdout.capturing(() -> new LockSession(game, game, game,
                remembering(COLLIDING_START, lock014())).run());

        assertEquals(1, game.breaks, "the worn pick breaks on the first strain: " + log);
        assertNotEquals(new Move(2, +1), game.played.get(1),
                "the very next thing sent was the slide that had just been refused: " + game.played);
        assertEquals(1, game.strains, "and so it strained twice over: " + log);
        assertTrue(log.contains("Keeping everything the lock has actually shown me"),
                "the break line must not claim it kept a row it just threw away: " + log);
        assertTrue(game.opened(), "a broken pick costs the pick, not the lock: " + log);
    }

    /**
     * When a false recall cannot be recovered from, the run must still not blame the reader. Nothing
     * moving is normally proof that the plate count or the offsets were misread - but only for a run
     * whose every row came off the screen. A run that recalled a model has a second explanation, and
     * on the evidence it is the likelier one: it was handed another chest's connections. Telling the
     * player to report a vision bug over a frame that read perfectly is what sent this whole
     * investigation the wrong way.
     */
    @Test
    void aFalseRecallThatCannotBeRecoveredBlamesTheMemoryAndNotTheReader() {
        // Plates 0 (+3) and 1 (-3) are on the walls and every plate drags one or both, so nothing can
        // move in either direction - what a wrong model looks like from inside the session.
        LockModel truth = LockModel.of(new int[] {3, -3, 0, 0},
                rows(row(n(1)), row(n(0)), row(n(0), n(1)), row(n(0), n(1))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        Connection[][] plausibleButWrong = rows(row(), row(), row(), row());

        String log = Stdout.capturing(() -> new LockSession(game, game, game,
                remembering(new int[] {3, -3, 0, 0}, plausibleButWrong)).run());

        assertFalse(game.opened());
        assertTrue(game.dumps.contains("false-recall"), "dumped as what it is: " + game.dumps);
        assertFalse(game.dumps.contains("wrong-model"),
                "the reader is not on trial when a memory was in play: " + game.dumps);
        assertTrue(log.contains("a different chest that happens to start at the same offsets"), log);
        assertFalse(log.contains("bug in this tool"), "nothing here says report a reader bug: " + log);
    }

    /**
     * The offsets the collision happened on, and both chests. {@code lock 014} of the bundled
     * catalogue is real and its model is right - it is simply not the chest in front of us.
     */
    private static final int[] COLLIDING_START = {3, 3, -3, -2};

    /** src/main/resources/known-locks.txt, lock 014: {@code 0:1I,2I,3N  1:2N  3:1I,2I}. */
    private static Connection[][] lock014() {
        return rows(row(i(1), i(2), n(3)), row(n(2)), row(), row(i(1), i(2)));
    }

    /**
     * The chest actually on screen. Its plate 2 drags plate 0 the same way - which is exactly what the
     * strain proved, since plate 0 sat at +3 and the slide would have pushed it off the track. Every
     * other row is left empty: the connections were never learned, because the run never got that far.
     */
    private static Connection[][] theChestOnScreen() {
        return rows(row(), row(), row(n(0)), row());
    }

    /**
     * The retry path. F8 pressed on a lock a previous run left half-solved: the offsets key nothing,
     * but the first row this run probes for itself names the chest, and the rest is recalled.
     */
    @Test
    void aHalfSolvedLockIsRecognisedFromTheFirstRowItProbes() {
        int[] midRun = {0, 1, 0, -2, 1, 1}; // not the state the lock was ever remembered at
        LockModel truth = LockModel.of(midRun, realLock());
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        Remembered known = remembering(START, realLock()); // keyed only by the pristine offsets

        String log = Stdout.capturing(() -> new LockSession(game, game, game, known).run());

        assertTrue(game.opened());
        assertTrue(log.contains("matches every row probed so far"),
                "one probed row should have named the lock: " + log);
    }

    /**
     * A memory that is not a usable model at all - the wrong number of plates, or a row missing - is
     * refused before it can be adopted. Neither should ever be handed over, but this is the one place
     * a bad file could reach the control loop, so it is checked rather than assumed.
     */
    @Test
    void aMemoryThatIsNotAModelOfThisLockIsRefused() {
        LockModel truth = LockModel.of(START, realLock());
        Remembered wrongSize = new Remembered();
        wrongSize.put(START, rows(row(), row(), row())); // three rows for a six-plate lock
        Remembered holed = new Remembered();
        Connection[][] withAHole = realLock();
        withAHole[2] = null;
        holed.put(START, withAHole);

        for (KnownLocks known : List.of(wrongSize, holed)) {
            FakeGame game = new FakeGame(truth, Skill.MASTER);
            String log = Stdout.capturing(() -> new LockSession(game, game, game, known).run());
            assertTrue(game.opened(), "the lock still opens the ordinary way");
            assertTrue(!log.contains("opened this lock before"), "nothing claimed recognition: " + log);
        }
    }

    /**
     * A remembered row the lock contradicts by having <i>more</i> connections than the memory. This is
     * the direction the skill mechanic cannot explain - training only ever <b>removes</b> a connection
     * - so it is also the direction that is not safe: a memory thinner than the truth is a
     * <i>subset</i>, and a subset can make an illegal move look legal. It means the offsets collided,
     * and the whole memory goes rather than the row it was caught on.
     *
     * <p>The memory used here is deliberately one connection lighter <i>and still solvable</i>, so it
     * is genuinely adopted. A model too thin to open the lock at all never gets that far - {@code
     * adopt} drops it before a key is pressed - which is a different test, and is why this one checks
     * the memory was taken up before checking what became of it.
     */
    @Test
    void aMemoryTheLockAddsAConnectionToIsNotThisChestAtAll() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        Connection[][] tooThin = trainedAwayOneConnection(realLock());

        String log = Stdout.capturing(() ->
                new LockSession(game, game, game, remembering(START, tooThin)).run());

        assertTrue(log.contains("opened this lock before"), "the memory must be adopted: " + log);
        assertTrue(game.opened(), log);
        assertFalse(log.contains("one connection fewer"),
                "gaining connections is not what training does: " + log);
        assertTrue(log.contains("it is a different one that starts at the same offsets"),
                "so the whole memory should go, not just that row: " + log);
    }

    /** Nothing remembered is the default, and it must change nothing at all. */
    @Test
    void rememberingNothingBehavesExactlyLikeBefore() {
        LockModel truth = LockModel.of(START, realLock());
        FakeGame plain = new FakeGame(truth, Skill.MASTER);
        FakeGame none = new FakeGame(truth, Skill.MASTER);

        Stdout.capturing(() -> new LockSession(plain, plain, plain).run());
        Stdout.capturing(() -> new LockSession(none, none, none, KnownLocks.NONE).run());

        assertEquals(plain.plays, none.plays);
        assertEquals(plain.strains, none.strains);
    }

    // --- a catalogue that lives entirely in the test ---

    private static Remembered remembering(LockModel lock) {
        return remembering(lock.start(), lock.connections());
    }

    private static Remembered remembering(int[] state, Connection[][] conn) {
        Remembered known = new Remembered();
        known.put(state, conn);
        return known;
    }

    /** The {@link KnownLocks} seam, backed by a map - what {@code LockCatalog} is, minus the files. */
    private static final class Remembered implements KnownLocks {

        private final Map<String, Connection[][]> byState = new HashMap<>();

        void put(int[] state, Connection[][] conn) {
            byState.put(java.util.Arrays.toString(state), conn);
        }

        @Override
        public Optional<Connection[][]> recall(int n, int[] state) {
            return Optional.ofNullable(byState.get(java.util.Arrays.toString(state)));
        }

        @Override
        public List<Connection[][]> matching(int n, Map<Integer, Connection[]> observedRows) {
            List<Connection[][]> out = new ArrayList<>();
            for (Connection[][] candidate : byState.values()) {
                if (candidate.length == n && agrees(candidate, observedRows)) {
                    out.add(candidate);
                }
            }
            return out;
        }

        private static boolean agrees(Connection[][] candidate, Map<Integer, Connection[]> observed) {
            for (Map.Entry<Integer, Connection[]> row : observed.entrySet()) {
                if (!java.util.Arrays.equals(candidate[row.getKey()], row.getValue())) {
                    return false;
                }
            }
            return true;
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
