package io.github.markosa84.colonysskeletonkey.control;

import java.awt.event.KeyEvent;

/**
 * A {@link Keyboard} that remembers, as a compact {@code W/S/A/D} string, every key it forwarded to
 * a delegate. This is the one seam every keystroke passes through - navigation (W/S), slides (A/D)
 * and the {@link KeySender#endCursor} re-homing S-bursts alike - so wrapping it here captures the
 * <i>whole</i> literal sequence the tool pressed to open a lock, for {@code lock-history.txt}.
 *
 * <p>It records <b>after</b> a successful delegate call, never before: {@link RobotKeyboard#tap}
 * throws {@link RobotKeyboard.FocusLost} <i>before</i> a key leaves the process, and a key that
 * never went out must not appear in the record.
 */
public final class RecordingKeyboard implements Keyboard {

    private final Keyboard delegate;
    private final StringBuilder keys = new StringBuilder();

    public RecordingKeyboard(Keyboard delegate) {
        this.delegate = delegate;
    }

    @Override
    public void tap(int vk) {
        delegate.tap(vk); // may throw FocusLost - then we record nothing, which is correct
        char c = letter(vk);
        if (c != 0) {
            keys.append(c);
        }
    }

    /** The keys forwarded since the last {@link #reset()}, e.g. {@code "SSSSSAAD"}. */
    public String recorded() {
        return keys.toString();
    }

    /** Clears the record. Called at the start of each F8 press, which reuses one keyboard. */
    public void reset() {
        keys.setLength(0);
    }

    /** The letter for a virtual key, or {@code 0} for anything the tool never sends. */
    private static char letter(int vk) {
        return switch (vk) {
            case KeyEvent.VK_W -> 'W';
            case KeyEvent.VK_S -> 'S';
            case KeyEvent.VK_A -> 'A';
            case KeyEvent.VK_D -> 'D';
            default -> 0;
        };
    }
}
