package io.github.markosa84.colonysskeletonkey.session;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.markosa84.colonysskeletonkey.Stdout;
import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.Cost;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.ModelRepair;
import io.github.markosa84.colonysskeletonkey.solver.Move;
import io.github.markosa84.colonysskeletonkey.solver.TestLocks;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
     * <b>A lock where nothing moves at all is not a hard lock - it is a misread one.</b> Every lock
     * the game hands you is openable, so from any configuration at least one slide is legal. If no
     * plate would move in either direction, the lock being driven is not the lock on screen.
     *
     * <p>A user hit exactly this: a 6-plate chest read as 4 plates (both end pins were too faint to
     * see), nine strains against plates that were never going to move, and then the session shrugged
     * and called the <i>lock</i> stuck. It must say what actually happened and save the frame, so the
     * next report arrives with the evidence in it.
     *
     * <p>The truth model here is deadlocked in both directions on every plate - which the real game
     * cannot produce, and that is the point: it is what a wrong model <i>looks like</i> from inside
     * the session. Compare {@link #deadlockedLockCostsTwoStrainsAndStopsInsteadOfRetrying}, where two
     * plates do move and the lock really is just stuck.
     */
    @Test
    void aLockOnWhichNothingEverMovesIsReportedAsAMisreadAndDumped() {
        // Plates 0 (+3) and 1 (-3) sit on the walls and every plate drags one or both of them, so
        // every slide would push somebody off the track: nothing can move, either way, anywhere.
        LockModel truth = LockModel.of(new int[] {3, -3, 0, 0},
                rows(row(n(1)), row(n(0)), row(n(0), n(1)), row(n(0), n(1))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        new LockSession(game, game, game).run();

        assertEquals(game.plays, game.strains, "every slide strained: not one moved a plate");
        assertTrue(game.dumps.contains("wrong-model"),
                "a lock nothing moves on must be dumped as the reader bug it is, not shrugged at");
        assertFalse(game.opened());
    }

    /**
     * <b>The endless loop, reproduced and defused.</b> On a frame darker than the calibration corpus a
     * reporter watched the tool slide one plate left and right forever until he alt-tabbed. The cause
     * was a misread offset: a slide the reader called safe (nothing at either end) still strained,
     * which recorded a refusal whose culprit set was empty - and an empty culprit never expires, so the
     * plate was wedged permanently, while the unblock planner kept "freeing" a plate that was already
     * free. Here the reader is made to misread plate 3 (always +3) as interior, so plate 4 looks free
     * yet strains whenever it drags plate 3 off its end. The run must <b>stop</b> - bounded, with the
     * frame saved as a misread - not spin. The timeout is the real assertion: without the fix this
     * test never returns.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aMisreadThatFakesASafeMoveStopsInsteadOfLooping() {
        // Plates 3 and 4 are mutually locked at the +3 end (each drags the other off it); plates 0-2
        // drag nothing. The reader is told plate 3 sits interior, so plate 4 reads as free to slide.
        LockModel truth = LockModel.of(new int[] {1, 1, 1, 3, 3},
                rows(row(), row(), row(), row(i(4)), row(i(3))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.misread = s -> {
            if (s[3] == 3) s[3] = 1; // the one wrong offset that makes an impossible move look safe
            return s;
        };

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened(), "an impossible-to-read lock cannot be opened, only reported");
        assertTrue(game.dumps.contains("misread"),
                "a run of strains the read called impossible must be saved as a misread, " + game.dumps);
        assertTrue(log.contains("misreading"), log);
    }

    /**
     * <b>The premature "stuck", opened.</b> A solvable five-plate lock where the strain-free strategy
     * dead-ends: plates 1, 2 and 4 are frozen at one end (each drags another off it) and can only be
     * freed by dragging them with plate 3 - but the gamble that probes plate 0 first drags plate 3 to
     * its own end, where its freeing direction goes off-track. The old code reported "stuck: no move
     * left to try" here. The escalation walks plate 0 back until plate 3 is interior again, gambles it,
     * and the lock comes open - within the gamble budget, so no pick is lost.
     */
    @Test
    void aDiscoveryDeadlockIsOpenedByRepositioning() {
        // A real solvable five-plate lock (found by search over random connections at a stranding
        // start) where the strain-free strategy dead-ends: after probing what it safely can, every
        // remaining gamble is off-track or already refused, so the old code reported "stuck". The
        // escalation shuffles the understood plates until an unprobed one can be gambled afresh.
        LockModel truth = LockModel.of(new int[] {2, 2, -3, -3, -3},
                rows(row(n(4)), row(i(3), n(4)), row(n(0), n(1), i(4)), row(), row(i(3))));
        assertTrue(LockSolver.solve(truth) != null, "the test lock must itself be openable");
        FakeGame game = new FakeGame(truth, Skill.MASTER);

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(game.opened(), "reposition-then-gamble must open it, left at "
                + Arrays.toString(game.state) + "\n" + log);
        assertTrue(log.contains("repositioning"), "the deadlock must be broken by the escalation, not "
                + "the ordinary strategy\n" + log);
        assertEquals(0, game.breaks, "the gamble budget keeps it well short of a broken pick");
    }

    /**
     * The verbose trace the run log carries for a bug report: a line per move with the tier that chose
     * it and the state before and after, the solution plan when it is computed, and the learned model
     * at the end. It is what turns "it got stuck" into a report someone can act on.
     */
    @Test
    void theTraceRecordsEveryMoveAndTheLearnedModel() throws Exception {
        LockModel truth = LockModel.of(new int[] {2, 2, -3, -3, -3},
                rows(row(n(4)), row(i(3), n(4)), row(n(0), n(1), i(4)), row(), row(i(3))));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        LockSession session = new LockSession(game, game, game);
        session.traceTo(new java.io.PrintStream(buf, true, "UTF-8"));

        Stdout.capturing(session::run);

        String trace = buf.toString("UTF-8");
        assertTrue(game.opened());
        assertTrue(trace.contains("detected 5 plates"), trace);
        assertTrue(trace.contains("step 1 ["), "a move-by-move trace with tiers: " + trace);
        assertTrue(trace.contains("[solving]") && trace.contains("solve from"), trace);
        assertTrue(trace.contains("model, as learned:"), trace);
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
        // Plate 2 sitting off-centre shadows its neighbour plate 1's row - and clears once every
        // plate is home, so the all-zero goal is directly readable (a centred plate no longer hides
        // anything, which is what the pin-pop used to guarantee and the 1280x720 floor now does).
        game.hideWhen = s -> s[2] != 0;

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
        // A cover over plate 1's row in the resting frame that clears the moment anything moves - the
        // session cannot read it where it sits, so it must nudge a visible plate to shift the geometry
        // before it can even start. Every later frame, including the all-zero goal, reads directly.
        game.hideWhen = s -> java.util.Arrays.equals(s, truth.start());

        new LockSession(game, game, game).run();

        assertTrue(game.opened());
        assertEquals(0, game.strains);
    }

    /**
     * A row hidden in the all-zero goal configuration <b>itself</b> can never be directly confirmed
     * open. The pin-pop used to read a centred plate whatever covered its holes; with the pop gone
     * the goal is confirmed only from a fresh all-zero hole read, so the session model-fills its way
     * to the goal, finds the direct read still short a row, nudges, re-solves, and comes back to the
     * same place - a no-progress loop. Rather than hang (or spend picks), the livelock guard stops it
     * and saves the frame. Above the 1280x720 floor a centred plate no longer hides anything, so this
     * is the accepted residual of dropping the pop, pinned here so the give-up stays graceful.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void aRowHiddenAtTheGoalItselfIsGivenUpOnGracefullyNotHung() {
        LockModel truth = LockModel.of(new int[] {1, -1, 1, 0},
                rows(row(), row(n(0)), row(), row()));
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        game.hiddenPlate = 1;
        game.hideWhen = s -> s[3] == 0; // plate 1 hidden whenever plate 3 is centred - as it is at goal

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertFalse(game.opened(), "a plate invisible at the all-zero goal can never be confirmed open");
        assertTrue(game.dumps.contains("no-progress"), game.dumps.toString());
        assertTrue(log.contains("returning to the same configuration"), log);
        assertEquals(0, game.strains, "the give-up costs no strain - it is a read limit, not a hard lock");
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
        // Live, a real lock is always openable, so a fully-learned model that will not solve means a
        // misread while probing - saved as evidence rather than shrugged at as a hard lock.
        assertTrue(game.dumps.contains("unsolvable-model"), game.dumps.toString());
    }

    /**
     * The four dark-frame reports in {@code captures/4} (2560x1440, in-game brightness turned down):
     * each learned a 6-plate model that will not open from the state the reader last saw, because a
     * misread while probing corrupted one connection. Every model is exactly as its {@code f8-*.log}
     * printed it. The recovery hinges on a single fact: since the real lock is openable, one row-edit
     * must restore solvability - so the search must find a suspect plate to re-probe for every one of
     * them. This pins the search directly against the real failures, no reader or frames required.
     */
    @Test
    void recoveryFindsASuspectForEveryReportedDarkFrameModel() {
        record Report(String name, int[] state, Connection[][] model) {}
        List<Report> reports = List.of(
                new Report("22:25", new int[] {1, -1, 1, 0, 0, 3}, rows(
                        row(n(4), i(5)),
                        row(i(0), i(3), n(4), i(5)),
                        row(n(3)),
                        row(n(2)),
                        row(n(1), n(2), i(3)),
                        row())),
                new Report("23:04", new int[] {0, 1, 0, 2, 1, -3}, rows(
                        row(n(2), n(4)),
                        row(n(0)),
                        row(i(3)),
                        row(n(2), i(4), n(5)),
                        row(n(2)),
                        row(i(0)))),
                new Report("23:06", new int[] {-3, 1, -1, 0, -1, -2}, rows(
                        row(n(2), n(4)),
                        row(n(0)),
                        row(i(3)),
                        row(n(2), i(4), n(5)),
                        row(),
                        row(i(0), i(2)))),
                new Report("23:09", new int[] {-2, 2, 0, -2, 2, 2}, rows(
                        row(n(1), i(2)),
                        row(n(3)),
                        row(n(0), i(1), i(4)),
                        row(),
                        row(n(0), i(2), i(5)),
                        row(i(1), n(4)))));
        for (Report r : reports) {
            LockModel m = LockModel.of(r.state(), r.model());
            assertNull(LockSolver.solve(m, r.state(), m.n() - 1, Cost.WALLCLOCK),
                    r.name() + " must be unsolvable exactly as reported");
            int suspect = -1;
            for (int p = 0; p < m.n() && suspect < 0; p++) {
                if (ModelRepair.singleEditRank(m, r.state(), p) != Integer.MAX_VALUE) {
                    suspect = p;
                }
            }
            assertTrue(suspect >= 0, r.name() + ": recovery must find a single-edit suspect to re-probe");
        }
    }

    /**
     * The whole recovery, end to end: a solvable lock whose reader flips one connection's sense while
     * probing, then reads true. Plates 0 and 1 are a mutual pair - moving 0 drags 1 the same way,
     * moving 1 drags 0 the opposite way - which the lock needs to open from a two-apart start. While
     * the misread is on, plate 0's offset reads negated, so plate 1's probe learns its drag of plate 0
     * as NORMAL: now the pair moves rigidly together and the two-apart lock can never centre - a
     * genuinely unopenable learned model, exactly what a probing misread produces. The old code dumped
     * {@code unsolvable-model} here; now the run clears the pair's misread plate, re-probes it once the
     * misread has passed, learns the true opposite drag, and the lock comes open - the offline
     * stand-in for the live 1440p failures, since no more live frames are coming.
     */
    @Test
    void recoveryReprobesAMisreadConnectionAndOpensTheLock() {
        LockModel truth = LockModel.of(new int[] {2, -2, 0, 0},
                rows(row(n(1)), row(i(0)), row(), row()));
        assertTrue(LockSolver.solve(truth) != null, "the truth lock must itself be openable");
        FakeGame game = new FakeGame(truth, Skill.MASTER);
        // Plate 0 reads negated until the probing that corrupts plate 1 is behind us. Discovery runs
        // high plate to low, so plate 1 is probed at play 3; recovery re-probes it well after play 3.
        game.misread = s -> {
            if (game.plays > 3) {
                return s;
            }
            int[] r = s.clone();
            r[0] = -r[0];
            return r;
        };

        String log = Stdout.capturing(() -> new LockSession(game, game, game).run());

        assertTrue(game.opened(), "recovery must re-probe the misread plate and open the lock\n" + log);
        assertTrue(log.contains("Re-probing plate"),
                "the unsolvable model must trigger a recovery re-probe\n" + log);
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
     * is a solved lock, not a failure: the move the plan predicted would reach the goal is what
     * landed, so the run concludes solved without reading the frame that came after.
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
