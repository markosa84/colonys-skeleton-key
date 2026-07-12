package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The focus gate. {@code Robot} synthesizes OS-level input into whatever window has focus, so this
 * check is the only thing standing between the tool and typing {@code W/A/S/D} into the player's
 * browser. It has to fire <b>before</b> the key leaves the process, and it has to be re-checked on
 * every single tap - the F8 gate only covers the start of a session, and a session runs for seconds.
 */
class RobotKeyboardTest {

    /** Records the Robot calls a tap would make, in order, instead of making them. */
    private static final class RecordingTaps implements RobotKeyboard.Taps {
        final List<String> calls = new ArrayList<>();

        @Override
        public void press(int vk) {
            calls.add("press " + vk);
        }

        @Override
        public void release(int vk) {
            calls.add("release " + vk);
        }

        @Override
        public void delay(int ms) {
            calls.add("delay " + ms);
        }
    }

    @Test
    void aFocusedGameGetsPressHoldReleaseGap() {
        RecordingTaps taps = new RecordingTaps();
        RobotKeyboard keyboard = new RobotKeyboard(taps, () -> true);

        keyboard.tap(KeyEvent.VK_A);

        assertEquals(List.of(
                "press " + KeyEvent.VK_A,
                "delay " + RobotKeyboard.HOLD_MS,
                "release " + KeyEvent.VK_A,
                "delay " + RobotKeyboard.GAP_MS), taps.calls);
    }

    /** Not one key may reach the outside world once the game is no longer in front. */
    @Test
    void aLostFocusThrowsBeforeAnyKeyIsSent() {
        RecordingTaps taps = new RecordingTaps();
        RobotKeyboard keyboard = new RobotKeyboard(taps, () -> false);

        RobotKeyboard.FocusLost e =
                assertThrows(RobotKeyboard.FocusLost.class, () -> keyboard.tap(KeyEvent.VK_A));

        assertTrue(taps.calls.isEmpty(), "the gate must precede the key, not follow it");
        assertTrue(e.getMessage().contains("foreground"), e.getMessage());
    }

    /** The gate is per tap: an alt-tab mid-session must stop the very next key. */
    @Test
    void theGateIsRecheckedOnEveryTap() {
        RecordingTaps taps = new RecordingTaps();
        boolean[] focused = {true};
        RobotKeyboard keyboard = new RobotKeyboard(taps, () -> focused[0]);

        keyboard.tap(KeyEvent.VK_W);
        focused[0] = false; // the player alt-tabbed away
        assertThrows(RobotKeyboard.FocusLost.class, () -> keyboard.tap(KeyEvent.VK_S));

        assertEquals(4, taps.calls.size(), "only the first tap's four calls ever happened");
    }
}
