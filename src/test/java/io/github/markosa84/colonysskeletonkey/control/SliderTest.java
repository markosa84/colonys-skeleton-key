package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.session.MoveExecutor;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.Move;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outcome classification against scripted frames. The timing is injected near-zero, so the
 * polling loop runs instantaneously; what is under test is the logic that decides MOVED /
 * UNCHANGED / RESET and reads a break off the pick-counter fingerprint - the behavioral truths
 * measured against the live game.
 */
class SliderTest {

    /** Near-zero timing: no settle floor, no break wait, 150ms give-up, 40ms partial floor. */
    private static final Slider.Timing FAST = new Slider.Timing(0, 0, 150, 0, 2, 40);

    private static final int U = LockModel.UNKNOWN;

    private static final long[] SAME_PICKS = {7};
    private static final long[] PICK_BROKE = {1, 2};

    private final RecordingKeyboard keyboard = new RecordingKeyboard();

    @Test
    void aMidSlideFrameMeansStillMovingAndTheMoveLands() {
        // The moving plate reads UNKNOWN mid-slide, then the settled state twice.
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS,
                new int[] {U, 0}, new int[] {1, 0}, new int[] {1, 0});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.MOVED, obs.outcome());
        assertArrayEquals(new int[] {1, 0}, obs.state());
        assertFalse(obs.pickBroke());
    }

    @Test
    void anUnchangedLockIsAStrain() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS,
                new int[] {0, 0}, new int[] {0, 0});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.UNCHANGED, obs.outcome());
        assertFalse(obs.pickBroke());
    }

    @Test
    void aMultiStepChangeIsAResetNotOurMove() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS,
                new int[] {0, 3}, new int[] {0, 3});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {2, 3}, new Move(0, -1));

        assertEquals(MoveExecutor.Outcome.RESET, obs.outcome());
    }

    @Test
    void aFingerprintChangeIsABreakEvenWhenThePlatesLookUntouched() {
        ScriptedPoller poller = new ScriptedPoller(PICK_BROKE,
                new int[] {2, 3}, new int[] {2, 3});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {2, 3}, new Move(0, -1));

        assertTrue(obs.pickBroke());
        assertEquals(MoveExecutor.Outcome.UNCHANGED, obs.outcome(),
                "above skill 0 a break leaves the lock exactly where it was");
    }

    @Test
    void aBreakThatMovedEveryPlateHomeIsAReset() {
        ScriptedPoller poller = new ScriptedPoller(PICK_BROKE,
                new int[] {2, 3}, new int[] {2, 3},   // settled after the key
                new int[] {0, 0}, new int[] {0, 0});  // re-read after the break animation
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {2, 3}, new Move(0, -1));

        assertTrue(obs.pickBroke());
        assertEquals(MoveExecutor.Outcome.RESET, obs.outcome());
        assertArrayEquals(new int[] {0, 0}, obs.state());
    }

    @Test
    void aLockWithNothingReadableAtAllIsUnreadable() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {U, U});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        assertThrows(MoveExecutor.UnreadableFrame.class, () -> slider.settle(2));
    }

    @Test
    void aPersistentlyHiddenPlateSettlesAsAPartialState() {
        // The mover is visible and moved one step; the other plate's row stays hidden. After the
        // partial floor this is settled-and-occluded, not still-moving.
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {1, U});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(MoveExecutor.Outcome.MOVED, obs.outcome());
        assertArrayEquals(new int[] {1, U}, obs.state());
        assertFalse(obs.pickBroke());
    }

    @Test
    void aStrainWithAHiddenPlateReturnsTheFullPreMoveState() {
        // Every visible plate is unchanged; moves are atomic, so nothing moved - the caller's
        // fully-known pre-move state still stands, hidden plate included.
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {2, U});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        MoveExecutor.Observation obs = slider.play(2, new int[] {2, 3}, new Move(0, -1));

        assertEquals(MoveExecutor.Outcome.UNCHANGED, obs.outcome());
        assertArrayEquals(new int[] {2, 3}, obs.state(), "the pre-move state, fully known");
    }

    @Test
    void playNavigatesToThePlateBeforeSliding() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS,
                new int[] {1, 0}, new int[] {1, 0});
        Slider slider = new Slider(poller, new KeySender(keyboard, 1), FAST);

        slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(List.of(KeyEvent.VK_W, KeyEvent.VK_A), keyboard.taps);
    }

    @Test
    void aCleanlyLandedMoveNeverGrabsTheCounterTwice() {
        // Only a strain can break a pick, and a strain leaves the state unchanged - so a move that
        // landed is proof the counter did not move, and re-reading it would buy nothing.
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {1, 0}, new int[] {1, 0});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        slider.play(2, new int[] {0, 0}, new Move(0, +1));
        assertEquals(1, poller.print, "one baseline grab for the session, none after a clean move");

        // ...and the baseline carries over, so a second clean move grabs nothing at all.
        slider.play(2, new int[] {0, 0}, new Move(0, +1));
        assertEquals(1, poller.print);
    }

    @Test
    void aStrainStillReadsTheCounter() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {0, 0}, new int[] {0, 0});
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST);

        slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertEquals(2, poller.print, "the state did not change, so the pick may have broken");
    }

    @Test
    void telemetryCountsWhatASlideCosts() {
        ScriptedPoller poller = new ScriptedPoller(SAME_PICKS, new int[] {1, 0}, new int[] {1, 0});
        Telemetry telemetry = new Telemetry();
        Slider slider = new Slider(poller, new KeySender(keyboard, 0), FAST, telemetry);

        slider.play(2, new int[] {0, 0}, new Move(0, +1));

        assertSame(telemetry, slider.telemetry(), "AutoLockpick reports off this very instance");
        assertEquals(1, telemetry.slides());
        assertTrue(telemetry.summary(1_000_000_000L).contains("1 slides"));

        telemetry.reset();
        assertEquals(0, telemetry.slides());
        assertEquals("", telemetry.summary(1_000_000_000L), "nothing slid, nothing to report");
    }

    @Test
    void isSingleStepRejectsAnyPlateThatJumped() {
        assertTrue(Slider.isSingleStep(new int[] {0, 1}, new int[] {1, 0}));
        assertFalse(Slider.isSingleStep(new int[] {0, 3}, new int[] {0, 1}));
    }

    /** Serves the scripted frames in order, repeating the last one forever. */
    private static final class ScriptedPoller implements LockPoller {
        private final List<int[]> frames;
        private final long[] prints;
        private int frame;
        private int print;

        ScriptedPoller(long[] prints, int[]... frames) {
            this.prints = prints;
            this.frames = Arrays.asList(frames);
        }

        @Override
        public int[] readLock(int n) {
            return frames.get(Math.min(frame++, frames.size() - 1)).clone();
        }

        @Override
        public long pickFingerprint() {
            return prints[Math.min(print++, prints.length - 1)];
        }
    }

    private static final class RecordingKeyboard implements Keyboard {
        final List<Integer> taps = new ArrayList<>();

        @Override
        public void tap(int vk) {
            taps.add(vk);
        }
    }
}
