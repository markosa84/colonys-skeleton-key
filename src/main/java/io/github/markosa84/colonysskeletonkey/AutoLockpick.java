package io.github.markosa84.colonysskeletonkey;

import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import io.github.markosa84.colonysskeletonkey.control.KeySender;
import io.github.markosa84.colonysskeletonkey.control.RobotKeyboard;
import io.github.markosa84.colonysskeletonkey.control.Slider;
import io.github.markosa84.colonysskeletonkey.control.Telemetry;
import io.github.markosa84.colonysskeletonkey.session.LockSession;
import io.github.markosa84.colonysskeletonkey.session.LockView;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.vision.GameScreen;
import io.github.markosa84.colonysskeletonkey.vision.LiveLockView;
import io.github.markosa84.colonysskeletonkey.vision.LivePoller;
import io.github.markosa84.colonysskeletonkey.vision.LockReader;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;
import io.github.markosa84.colonysskeletonkey.win32.Win32;

/**
 * The Colony's Skeleton Key - background controller for the automated lockpicker.
 *
 * <p>Runs as a console app while the game stays in the foreground (borderless windowed, 4K). A poll
 * loop watches one global hotkey via {@link Win32}:
 *
 * <ul>
 *   <li><b>F8</b> - forget everything, then learn the open lock and solve it, in one go. The lock is
 *       never reset: probing is itself a sequence of legal moves, so wherever it ends is simply a
 *       closer state to solve from.</li>
 * </ul>
 *
 * Keys are only sent when the focused window looks like the game, so stray input never lands
 * elsewhere. Launch with {@code --enable-native-access=ALL-UNNAMED} and {@code -Dsun.java2d.uiScale=1}
 * (the Gradle {@code run} task sets both); quit with Ctrl-C.
 */
public final class AutoLockpick {

    /** Poll interval for the hotkey loop. */
    private static final int POLL_MS = 30;

    /** Executable that owns the focused window when the game is in front. */
    private static final String DEFAULT_GAME_PROCESS = "G1R-Win64-Shipping.exe";

    public static void main(String[] args) throws Exception {
        String gameProcess = resolveGameProcess(args);

        // Must precede any AWT use so screen capture yields true device pixels.
        Win32.setProcessDpiAware();

        // Device pixels, not DPI-scaled ones, thanks to the line above plus -Dsun.java2d.uiScale=1.
        Dimension display = Toolkit.getDefaultToolkit().getScreenSize();
        Viewport viewport = new Viewport(display.width, display.height);

        Robot robot = new Robot();
        GameScreen screen = new GameScreen(robot, viewport);
        LockReader reader = new LockReader(viewport);
        // Every tap re-checks the focus: a session outlives the F8 gate, and an alt-tab mid-run
        // must abort rather than type W/A/S/D into whatever got focused.
        RobotKeyboard keyboard = new RobotKeyboard(robot,
                () -> Win32.foregroundProcessName().equalsIgnoreCase(gameProcess));
        KeySender keys = new KeySender(keyboard, 0); // the session homes the real cursor
        Slider slider = new Slider(new LivePoller(screen, reader), keys, Slider.Timing.GAME);
        LockView view = new LiveLockView(screen, reader);

        printBanner(viewport, gameProcess);

        Hotkey f8 = new Hotkey(Win32.VK_F8, Win32::isKeyDown);
        while (true) {
            if (f8.pressed()) {
                run(slider.telemetry(), Win32::foregroundProcessName, gameProcess,
                        () -> new LockSession(view, keys, slider).run());
            }
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * The executable that must own the focused window before a single key is sent, matched
     * case-insensitively against the file name: the first CLI argument, else
     * {@code -Dgame.process=...}, else the game's own.
     *
     * <p>The gate keys on the <b>process</b>, not the window title. Titles are not safe: a Chrome tab
     * reading "BP Mod Loader and Console Enabler at Gothic 1 Remake Nexus" contains any sensible title
     * substring, and focusing it would have typed {@code W/A/S/D} into the browser. This gate is the
     * only thing keeping stray keys out of other windows.
     */
    static String resolveGameProcess(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        return System.getProperty("game.process", DEFAULT_GAME_PROCESS);
    }

    /** Edge-detects the global hotkey: true only on the poll where it goes from up to down. */
    static final class Hotkey {
        private final int vk;
        private final IntPredicate keyDown;
        private boolean wasDown;

        Hotkey(int vk, IntPredicate keyDown) {
            this.vk = vk;
            this.keyDown = keyDown;
        }

        boolean pressed() {
            boolean down = keyDown.test(vk);
            boolean edge = down && !wasDown;
            wasDown = down;
            return edge;
        }
    }

    /**
     * Runs a routine, but only while the game is focused, so stray keys never leak elsewhere.
     * Returns whether the body ran at all.
     */
    static boolean run(Telemetry telemetry, Supplier<String> foreground, String gameProcess,
            Runnable body) {
        String focused = foreground.get();
        if (!focused.equalsIgnoreCase(gameProcess)) {
            System.out.println("[F8] Ignored: the focused window belongs to "
                    + (focused.isEmpty() ? "an unknown process" : focused)
                    + ", not " + gameProcess + ".");
            return false;
        }
        System.out.println("[F8] Learning the open lock, then solving it...");
        telemetry.reset(); // each press reports its own run
        long t0 = System.nanoTime();
        try {
            body.run();
        } catch (RobotKeyboard.FocusLost e) {
            System.out.println("[F8] Aborted: " + e.getMessage()
                    + ". Refocus the game and press F8 again.");
            return true;
        }
        long elapsed = System.nanoTime() - t0;
        System.out.printf("[F8] Done in %.1fs.%n", elapsed / 1e9);
        String breakdown = telemetry.summary(elapsed);
        if (!breakdown.isEmpty()) {
            System.out.println(breakdown);
        }
        return true;
    }

    static void printBanner(Viewport viewport, String gameProcess) {
        System.out.println("=== The Colony's Skeleton Key ===");
        System.out.println("Nothing to configure: broken picks are read off the lockpick counter, so "
                + "the tool works at any lockpicking skill and notices if you train it.");
        System.out.println("Game: borderless windowed at " + viewport.width() + "x" + viewport.height()
                + (viewport.isReference()
                        ? " (the calibrated resolution)."
                        : " - scaled from the 4K calibration.")
                + " Keys are sent only while " + gameProcess + " owns the focused window.");
        System.out.println("Open a lock in-game, keep it focused, and press F8.");
        System.out.println("It learns the plate connections and opens the lock, without ever resetting.");
        System.out.println("Each press starts from scratch. Quit with Ctrl-C.");
        System.out.println("NOTE: the hotkey is observed, not swallowed - the game receives F8 too.");
        System.out.println("Check F8 is unbound in the game's controls menu before you start.");
        System.out.println("Reader calibrated for " + LockModel.MIN_PLATES + "-" + LockModel.MAX_PLATES
                + " plate locks, every size verified against labelled frames from the real game.");
        System.out.println("=================================");
    }
}
