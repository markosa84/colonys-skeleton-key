package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cursor arithmetic and, above all, the key conventions the whole tool hangs on:
 * {@code dir +1 = LEFT = A}, {@code dir -1 = RIGHT = D}, W selects a lower plate index, S a
 * higher one, and W/S clamp at the ends (which is what makes {@link KeySender#endCursor} sound).
 */
class KeySenderTest {

    private final RecordingKeyboard keyboard = new RecordingKeyboard();

    @Test
    void navigatingToALowerPlateTapsW() {
        KeySender keys = new KeySender(keyboard, 5);
        keys.navigateTo(2);
        assertEquals(List.of(KeyEvent.VK_W, KeyEvent.VK_W, KeyEvent.VK_W), keyboard.taps);
        assertEquals(2, keys.cursor());
    }

    @Test
    void navigatingToAHigherPlateTapsS() {
        KeySender keys = new KeySender(keyboard, 1);
        keys.navigateTo(3);
        assertEquals(List.of(KeyEvent.VK_S, KeyEvent.VK_S), keyboard.taps);
        assertEquals(3, keys.cursor());
    }

    @Test
    void navigatingToTheCurrentPlateSendsNothing() {
        KeySender keys = new KeySender(keyboard, 2);
        keys.navigateTo(2);
        assertEquals(List.of(), keyboard.taps);
    }

    /** The load-bearing convention: +1 = LEFT = A, -1 = RIGHT = D. */
    @Test
    void slideMapsDirectionsToTheDocumentedKeys() {
        KeySender keys = new KeySender(keyboard, 0);
        keys.slide(+1);
        keys.slide(-1);
        assertEquals(List.of(KeyEvent.VK_A, KeyEvent.VK_D), keyboard.taps);
    }

    @Test
    void endCursorSaturatesSAndLandsOnTheLastPlate() {
        KeySender keys = new KeySender(keyboard, 1); // wherever the game left it
        keys.endCursor(4);
        assertEquals(List.of(KeyEvent.VK_S, KeyEvent.VK_S, KeyEvent.VK_S, KeyEvent.VK_S),
                keyboard.taps);
        assertEquals(3, keys.cursor());
    }

    private static final class RecordingKeyboard implements Keyboard {
        final List<Integer> taps = new ArrayList<>();

        @Override
        public void tap(int vk) {
            taps.add(vk);
        }
    }
}
