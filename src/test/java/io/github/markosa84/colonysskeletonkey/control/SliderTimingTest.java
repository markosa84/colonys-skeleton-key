package io.github.markosa84.colonysskeletonkey.control;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.session.MoveExecutor;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.Move;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The animation contract itself, run against the <b>measured</b> {@link Slider.Timing#GAME} values -
 * a 300ms settle floor, a 5s pick-replacement animation, a 12s give-up - on a virtual clock. Real
 * time would make these tests take twenty seconds; virtual time makes them instant, and lets them
 * assert not just <i>what</i> the slider concluded but <i>when</i> it was entitled to conclude it.
 *
 * <p>{@link SliderTest} covers the outcome classification with near-zero timings. This covers the
 * clock.
 */
class SliderTimingTest {

    private static final int U = LockModel.UNKNOWN;
    private static final Slider.Timing GAME = Slider.Timing.GAME;

    private final FakeTicks ticks = new FakeTicks();
    private final RecordingKeyboard keyboard = new RecordingKeyboard();

    /**
     * The load-bearing floor. Nothing on screen has moved for the first ~112ms after the key, so a
     * lock that still reads unchanged says nothing yet - concluding "strain" there is exactly how a
     * successful move once got recorded as one.
     */
    @Test
    void aLockThatHasNotStartedMovingYetIsNotAStrain() {
        // The plates begin to move at 112ms, as measured against the live game.
        FakePoller poller = new FakePoller(ticks, ms -> ms < 112 ? new int[] {0, 0} : new int[] {1, 0});
        Slider slider = slider(poller, GAME);

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.MOVED, obs.outcome());
        assertArrayEquals(new int[] {1, 0}, obs.state());
        assertTrue(ticks.elapsedMs() >= GAME.settleMinMs(),
                "the slide must be watched out to the settle floor, not judged at 5ms");
    }

    /** The same frames, judged without the floor: the move looks like a strain. Hence the floor. */
    @Test
    void withoutTheSettleFloorTheSameSlideWouldReadAsAStrain() {
        FakePoller poller = new FakePoller(ticks, ms -> ms < 112 ? new int[] {0, 0} : new int[] {1, 0});
        Slider slider = slider(poller, new Slider.Timing(0, 0, 12_000, 5, 2, 1500));

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.UNCHANGED, obs.outcome(),
                "a zero floor concludes from frames taken before the game even started animating");
    }

    /**
     * A row that stays unreadable in a state that has stopped changing is settled-and-occluded, not
     * still-moving: nothing animates for 1.5s. It must come back with its UNKNOWN, and it must not
     * cost the full 12s give-up (the bug that once made a difficulty-4 chest look unsolvable).
     */
    @Test
    void aPersistentlyHiddenRowSettlesAtThePartialFloorRatherThanWaitingOutTheGiveUp() {
        FakePoller poller = new FakePoller(ticks, ms -> new int[] {1, U});
        Slider slider = slider(poller, GAME);

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.MOVED, obs.outcome());
        assertArrayEquals(new int[] {1, U}, obs.state(), "returned with the row it could not resolve");
        assertTrue(ticks.elapsedMs() >= GAME.partialAfterMs(), "waited the partial floor out");
        assertTrue(ticks.elapsedMs() < GAME.resetMaxMs(), "but nowhere near the 12s give-up");
    }

    /** Nothing readable at all is never a conclusion - it times out into UnreadableFrame. */
    @Test
    void aLockThatNeverBecomesReadableGivesUpAtTheLimit() {
        FakePoller poller = new FakePoller(ticks, ms -> new int[] {U, U});
        Slider slider = slider(poller, GAME);

        assertThrows(MoveExecutor.UnreadableFrame.class, () -> slider.settle(2));
        assertTrue(ticks.elapsedMs() >= GAME.resetMaxMs(),
                "it may only give up after the longest thing the lock can do");
    }

    /**
     * The game discards input for ~4-5s while it swaps a broken pick. Two strains sent into that
     * window once vanished, which made a second break look like it needed eight strains rather than
     * six. So a break is waited out before the lock is read again.
     */
    @Test
    void aBrokenPickIsWaitedOutBeforeTheLockIsReadAgain() {
        // The plates go home during the replacement animation (skill 0: a break resets the puzzle).
        FakePoller poller = new FakePoller(ticks,
                ms -> ms < GAME.breakAnimationMs() ? new int[] {2, 3} : new int[] {0, 0},
                1L, 2L); // the counter changed: a pick broke
        Slider slider = slider(poller, GAME);

        MoveExecutor.Observation obs = slider.play(2, new int[] {2, 3}, new Move(0, -1));

        assertTrue(obs.pickBroke());
        assertEquals(MoveExecutor.Outcome.RESET, obs.outcome());
        assertArrayEquals(new int[] {0, 0}, obs.state());
        assertTrue(ticks.sleeps.contains(GAME.breakAnimationMs()),
                "the pick-replacement animation must be slept out, not polled through");
    }

    /**
     * {@code settle} is called when time has passed that this slider did not cause - a fresh
     * session, a recovery pause - so its cached fingerprint is stale and the next move that could
     * strain must grab a fresh baseline. Otherwise a break would be diffed against a number from
     * before the pause and missed.
     */
    @Test
    void settleDropsTheCachedCounterSoTheNextStrainIsStillSeen() {
        int[][] lock = {{1, 0}};
        FakePoller poller = new FakePoller(ticks, ms -> lock[0]);
        Slider slider = slider(poller, GAME);

        slider.play(2, new int[] {0, 0}, new Move(0, +1)); // clean move: one baseline grab
        assertEquals(1, poller.prints);

        slider.settle(2);
        assertEquals(1, poller.prints, "settling reads the lock, never the counter");

        lock[0] = new int[] {2, 0};
        slider.play(2, new int[] {1, 0}, new Move(0, +1));

        assertEquals(2, poller.prints, "the cached fingerprint was dropped, so a new baseline is due");
    }

    private Slider slider(LockPoller poller, Slider.Timing timing) {
        return new Slider(poller, new KeySender(keyboard, 0), timing, new Telemetry(), ticks);
    }

    /** A clock the test owns. Time moves only when the slider sleeps, so a run is instantaneous. */
    private static final class FakeTicks implements Slider.Ticks {
        private final long start = 1_000_000_000L; // not zero, so a bug reading "0" is visible
        private long now = start;
        final List<Integer> sleeps = new ArrayList<>();

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleep(int ms) {
            sleeps.add(ms);
            // At least a tick, so a 0ms poll interval advances time instead of spinning forever.
            now += Math.max(1, ms) * 1_000_000L;
        }

        long elapsedMs() {
            return (now - start) / 1_000_000L;
        }
    }

    /** Serves whatever the lock looks like at the current virtual time. */
    private static final class FakePoller implements LockPoller {
        private final FakeTicks ticks;
        private final LongFunction<int[]> frameAt;
        private final long[] fingerprints;
        int prints;

        FakePoller(FakeTicks ticks, LongFunction<int[]> frameAt, long... fingerprints) {
            this.ticks = ticks;
            this.frameAt = frameAt;
            this.fingerprints = fingerprints.length == 0 ? new long[] {7L} : fingerprints;
        }

        @Override
        public int[] readLock(int n) {
            return frameAt.apply(ticks.elapsedMs()).clone();
        }

        @Override
        public long pickFingerprint() {
            return fingerprints[Math.min(prints++, fingerprints.length - 1)];
        }
    }

    private static final class RecordingKeyboard implements Keyboard {
        @Override
        public void tap(int vk) {
        }
    }
}
