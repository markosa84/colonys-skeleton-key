package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;

import io.github.markosa84.colonysskeletonkey.session.CursorKeys;

/**
 * Types the lock's keys: W selects a lower plate index, S a higher one, A slides the selected
 * plate LEFT (dir +1), D slides it RIGHT (dir -1).
 *
 * <p>Tracks the selected-plate cursor so callers can address plates by index. The game never
 * shows which plate is selected (the front plate is drawn dark whichever plate it is), so the
 * cursor must be tracked from a known point - and {@link #endCursor} is how a known point is
 * re-established when the game moves the selection on its own.
 */
public final class KeySender implements CursorKeys {

    private final Keyboard keyboard;
    private int cursor; // currently selected plate index

    public KeySender(Keyboard keyboard, int startCursor) {
        this.keyboard = keyboard;
        this.cursor = startCursor;
    }

    @Override
    public int cursor() {
        return cursor;
    }

    /** Moves the selection to plate index {@code p} (W to lower index, S to higher). */
    void navigateTo(int p) {
        while (cursor > p) {
            keyboard.tap(KeyEvent.VK_W);
            cursor--;
        }
        while (cursor < p) {
            keyboard.tap(KeyEvent.VK_S);
            cursor++;
        }
    }

    /** Slides the currently selected plate: dir +1 = LEFT (A), dir -1 = RIGHT (D). */
    void slide(int dir) {
        keyboard.tap(dir > 0 ? KeyEvent.VK_A : KeyEvent.VK_D);
    }

    /**
     * Forces the selection onto the last plate by saturating S presses. W/S clamp at the ends
     * (verified live), so this always lands on plate {@code n-1} whatever the game was doing
     * with the selection.
     */
    @Override
    public void endCursor(int n) {
        for (int i = 0; i < n; i++) {
            keyboard.tap(KeyEvent.VK_S);
        }
        cursor = n - 1;
    }
}
