package io.github.markosa84.colonysskeletonkey.control;

import java.awt.Robot;
import java.util.function.BooleanSupplier;

/**
 * Taps keys via {@code java.awt.Robot}, which synthesizes OS-level input delivered to whatever
 * window has focus - which is exactly why every tap first checks that the game still owns the
 * focus. The F8 gate covers the start of a session, but a session can run for many seconds; if
 * the player alt-tabs away mid-run, the next tap would land W/A/S/D in whatever they switched
 * to. Instead it throws {@link FocusLost} and the session aborts cleanly.
 *
 * <p>Key timing, measured against the live game: both a slide and a W/S selection change register
 * with a <b>0ms hold and a 0ms gap</b> - the game reads discrete key events, not a per-frame
 * keyboard poll, and it <i>queues</i> slides rather than dropping them (six sent back-to-back all
 * landed). These values are margin, not necessity.
 *
 * <p>What the game <i>does</i> discard is input sent while the lock is animating a <b>reset</b>.
 * Timing that is {@link Slider}'s job, not this class's.
 */
public final class RobotKeyboard implements Keyboard {

    /** Thrown before a tap would land in a window that is not the game's. */
    public static final class FocusLost extends RuntimeException {
        public FocusLost(String message) {
            super(message);
        }
    }

    /**
     * The three calls a tap makes on the {@code Robot}. It is a seam only because {@code Robot}
     * cannot be constructed in a headless JVM, so the tests - which are headless - could otherwise
     * not reach the one thing in this class worth pinning: that the focus gate fires <i>before</i>
     * any key leaves the process.
     */
    interface Taps {
        void press(int vk);

        void release(int vk);

        void delay(int ms);
    }

    static final int HOLD_MS = 5;
    static final int GAP_MS = 5;

    private final Taps taps;
    private final BooleanSupplier gameFocused;

    public RobotKeyboard(Robot robot, BooleanSupplier gameFocused) {
        this(robotTaps(robot), gameFocused);
    }

    RobotKeyboard(Taps taps, BooleanSupplier gameFocused) {
        this.taps = taps;
        this.gameFocused = gameFocused;
    }

    private static Taps robotTaps(Robot robot) {
        return new Taps() {
            @Override
            public void press(int vk) {
                robot.keyPress(vk);
            }

            @Override
            public void release(int vk) {
                robot.keyRelease(vk);
            }

            @Override
            public void delay(int ms) {
                robot.delay(ms);
            }
        };
    }

    @Override
    public void tap(int vk) {
        if (!gameFocused.getAsBoolean()) {
            throw new FocusLost("the game lost the foreground mid-run");
        }
        taps.press(vk);
        taps.delay(HOLD_MS);
        taps.release(vk);
        taps.delay(GAP_MS);
    }
}
