package io.github.markosa84.colonysskeletonkey.control;

/** Taps one key. The only thing {@link KeySender} needs from the outside world. */
public interface Keyboard {

    /** Presses and releases the given {@link java.awt.event.KeyEvent} virtual key once. */
    void tap(int vk);
}
