package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The keystroke recorder behind {@code lock-history.txt}. Its one job is to capture the exact
 * {@code W/S/A/D} stream that reached the game - no less (the {@code endCursor} S-bursts count too,
 * being keys the tool pressed) and, crucially, no more: a key the focus gate refused never landed, so
 * it must not appear in a history that claims to be what opened the lock.
 */
class RecordingKeyboardTest {

    /** A delegate that just remembers the virtual keys it was handed, in order. */
    private static final class SpyKeyboard implements Keyboard {
        final List<Integer> taps = new ArrayList<>();

        @Override
        public void tap(int vk) {
            taps.add(vk);
        }
    }

    @Test
    void recordsEveryForwardedKeyAsItsLetter() {
        SpyKeyboard spy = new SpyKeyboard();
        RecordingKeyboard rec = new RecordingKeyboard(spy);

        for (int vk : new int[] {KeyEvent.VK_S, KeyEvent.VK_S, KeyEvent.VK_A,
                KeyEvent.VK_A, KeyEvent.VK_D, KeyEvent.VK_W}) {
            rec.tap(vk);
        }

        assertEquals("SSAADW", rec.recorded());
        assertEquals(6, spy.taps.size(), "every key is also forwarded to the real keyboard");
    }

    @Test
    void ignoresKeysTheToolNeverSends() {
        RecordingKeyboard rec = new RecordingKeyboard(new SpyKeyboard());

        rec.tap(KeyEvent.VK_A);
        rec.tap(KeyEvent.VK_R); // unbound - reset is the game's key, never ours
        rec.tap(KeyEvent.VK_D);

        assertEquals("AD", rec.recorded());
    }

    @Test
    void resetClearsBetweenPresses() {
        RecordingKeyboard rec = new RecordingKeyboard(new SpyKeyboard());
        rec.tap(KeyEvent.VK_A);
        rec.tap(KeyEvent.VK_D);

        rec.reset();
        rec.tap(KeyEvent.VK_W);

        assertEquals("W", rec.recorded(), "one keyboard is reused, so a press starts from empty");
    }

    /** A key the delegate refused (focus lost before it went out) must leave no trace. */
    @Test
    void aRefusedKeyIsNotRecorded() {
        Keyboard refusing = vk -> {
            throw new RobotKeyboard.FocusLost("gone");
        };
        RecordingKeyboard rec = new RecordingKeyboard(refusing);

        assertThrows(RobotKeyboard.FocusLost.class, () -> rec.tap(KeyEvent.VK_A));

        assertEquals("", rec.recorded(), "the record must be the keys that actually landed");
    }

    /**
     * Every key {@link KeySender} sends is recorded, whatever its <i>purpose</i>. A probe move and a
     * solving move are the same navigate-then-slide to the keyboard - there is no probe/solve flag to
     * filter on - so the history's key stream necessarily includes the connection-probing moves too.
     * Driven through a real {@code KeySender} to prove the whole chain, re-homing S-bursts and all.
     */
    @Test
    void everyKeySenderKeyIsRecordedProbingMovesIncluded() {
        RecordingKeyboard rec = new RecordingKeyboard(vk -> { /* a Robot would be here, live */ });
        KeySender keys = new KeySender(rec, 0);

        keys.navigateTo(2); // probe plate 2: two S to select it...
        keys.slide(+1);     // ...then one A to slide it - a probing gamble, indistinguishable from...
        keys.navigateTo(0); // ...a solving move: W back up to plate 0...
        keys.slide(-1);     // ...and D to slide it
        keys.endCursor(4);  // re-home the cursor: four saturating S

        assertEquals("SSAWWDSSSS", rec.recorded(),
                "navigation, probe slides, solve slides and re-homing all reach the record");
    }
}
