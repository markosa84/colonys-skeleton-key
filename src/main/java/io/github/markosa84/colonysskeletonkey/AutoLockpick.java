package io.github.markosa84.colonysskeletonkey;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import javax.imageio.ImageIO;

import io.github.markosa84.colonysskeletonkey.control.KeySender;
import io.github.markosa84.colonysskeletonkey.control.RobotKeyboard;
import io.github.markosa84.colonysskeletonkey.control.Slider;
import io.github.markosa84.colonysskeletonkey.control.Telemetry;
import io.github.markosa84.colonysskeletonkey.session.LockSession;
import io.github.markosa84.colonysskeletonkey.session.LockView;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.vision.GameScreen;
import io.github.markosa84.colonysskeletonkey.vision.LatticeReader;
import io.github.markosa84.colonysskeletonkey.vision.LiveLockView;
import io.github.markosa84.colonysskeletonkey.vision.LivePoller;
import io.github.markosa84.colonysskeletonkey.vision.LockAnalyzer;
import io.github.markosa84.colonysskeletonkey.vision.LockReader;
import io.github.markosa84.colonysskeletonkey.vision.Tone;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;
import io.github.markosa84.colonysskeletonkey.win32.Win32;

/**
 * The Colony's Skeleton Key - background controller for the automated lockpicker.
 *
 * <p>Runs as a console app while the game stays in the foreground (borderless windowed, any
 * resolution). A poll loop watches one global hotkey via {@link Win32}:
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
 *
 * <p>{@code --diagnose <frame.png>} replays a saved capture through the reader instead of running
 * the app, so a bug report can be answered from the file the reporter attached.
 */
public final class AutoLockpick {

    /** Poll interval for the hotkey loop. */
    private static final int POLL_MS = 30;

    /** Executable that owns the focused window when the game is in front. */
    private static final String DEFAULT_GAME_PROCESS = "G1R-Win64-Shipping.exe";

    /**
     * Which reader to use. The relative, tone-free {@code lattice} reader is the default: it matches
     * the pixel-calibrated one on every labelled frame and also reads HDR frames the calibrated one
     * cannot. {@code --reader=legacy} still selects the old one. See {@link #analyzer}.
     */
    private static final String DEFAULT_READER = "lattice";

    /**
     * Smallest client area we will believe is the game. Below this the window is a splash, a
     * tooltip, or something else that briefly owns the foreground, and its rectangle would send the
     * reader hunting in a 200px box; the display is a safer guess.
     */
    private static final int MIN_GAME_WIDTH = 640, MIN_GAME_HEIGHT = 480;

    public static void main(String[] args) throws Exception {
        String readerKind = resolveReader(args);
        Optional<Path> frame = diagnoseArg(args);
        if (frame.isPresent()) {
            System.exit(diagnose(frame.get(), readerKind) ? 0 : 1);
        }
        String gameProcess = resolveGameProcess(args);
        boolean dumping = dumpMode(args);

        // Must precede any AWT use so screen capture yields true device pixels.
        String dpi = Win32.setProcessDpiAware();

        Robot robot = new Robot();
        // Every tap re-checks the focus: a session outlives the F8 gate, and an alt-tab mid-run
        // must abort rather than type W/A/S/D into whatever got focused.
        RobotKeyboard keyboard = new RobotKeyboard(robot,
                () -> Win32.foregroundProcessName().equalsIgnoreCase(gameProcess));
        KeySender keys = new KeySender(keyboard, 0); // the session homes the real cursor

        printBanner(gameProcess, dpi);
        if (dumping) {
            System.out.println("--dump: F8 saves the game's view and the sidecar. Nothing is "
                    + "probed, no key is sent, no lockpick is spent.");
        }
        if (!readerKind.equals(DEFAULT_READER)) {
            System.out.println("--reader=" + readerKind + ": using the "
                    + ("legacy".equals(readerKind) ? "old pixel-calibrated reader"
                            : "lattice".equals(readerKind) ? "relative (tone-free) reader" : readerKind)
                    + " instead of the default.");
        }
        String warning = dpiWarning(awtScale());
        if (!warning.isEmpty()) {
            System.out.println(warning);
        }

        Hotkey f8 = new Hotkey(Win32.VK_F8, Win32::isKeyDown);
        while (true) {
            if (f8.pressed()) {
                // Resolved per press, not once at startup: the game is usually launched after this
                // tool, and a player can change resolution between two locks.
                Viewport viewport = resolveViewport(Win32.foregroundClientRect(), screenSize());
                // The gamma, read off the frame once per press. It cannot change while a lock is
                // open - you would have to leave the minigame to move the slider - so measuring it
                // per poll would buy nothing and cost a grab every time.
                Tone tone = Tone.estimate(new GameScreen(robot, viewport).capture(), viewport);
                GameScreen screen = new GameScreen(robot, viewport, tone);
                LockAnalyzer reader = analyzer(readerKind, viewport, tone);
                Slider slider = new Slider(new LivePoller(screen, reader), keys, Slider.Timing.GAME);
                LockView view = new LiveLockView(screen, reader,
                        () -> environment(gameProcess, dpi));
                if (dumping) {
                    dump(Win32::foregroundProcessName, gameProcess, view);
                } else {
                    run(slider.telemetry(), Win32::foregroundProcessName, gameProcess, () -> {
                        // Only inside the body, so it names the game's window and not some other
                        // window that happened to be focused when the gate turned the press away.
                        System.out.println("Reading the game's view at " + viewport + ".");
                        System.out.println(tone.describe());
                        new LockSession(view, keys, slider).run();
                    });
                }
            }
            Thread.sleep(POLL_MS);
        }
    }

    /**
     * The rectangle the game draws into, in virtual-desktop pixels: the focused window's client
     * area, falling back to the primary display when there is no plausible window to measure.
     *
     * <p>The display used to be the only answer, and it is the wrong one for anyone whose game does
     * not fill it - playing below the desktop resolution, in a window, or on a second monitor. The
     * reader then scans a rectangle the lock is not in, finds no pins, and reports "no lock" over a
     * screenshot that looks perfectly fine. Measure the window instead.
     */
    static Viewport resolveViewport(Optional<Win32.Rect> clientRect, Dimension display) {
        return clientRect
                .filter(r -> r.width() >= MIN_GAME_WIDTH && r.height() >= MIN_GAME_HEIGHT)
                .map(r -> new Viewport(r.x(), r.y(), r.width(), r.height()))
                .orElseGet(() -> new Viewport(display.width, display.height));
    }

    /**
     * The AWT scale of the primary display: 1.0 exactly when {@code -Dsun.java2d.uiScale=1} took
     * effect. Anything else and every capture is resampled behind our back.
     */
    private static double awtScale() {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().getDefaultTransform().getScaleX();
    }

    /**
     * The complaint to print when Java is DPI-scaling the screen - i.e. the tool was started without
     * {@code -Dsun.java2d.uiScale=1}, which the packaged exe and {@code lockpick.bat} both pass and
     * a bare {@code java -jar} does not.
     *
     * <p>Worth a check of its own because it cannot be caught downstream: {@code Robot} still hands
     * back an image of exactly the size asked for, merely resampled from a different region, so
     * every size assertion in the pipeline passes and only the reader notices, by finding nothing.
     */
    static String dpiWarning(double awtScale) {
        if (awtScale == 1.0) {
            return "";
        }
        return String.format(Locale.ROOT,
                "WARNING: Java is scaling the screen by %.2fx, so captures will not be true pixels "
                + "and no lock will be found.%n"
                + "  -Dsun.java2d.uiScale=1 is missing. Run the packaged exe, or lockpick.bat - "
                + "not `java -jar` and not from an IDE.", awtScale);
    }

    /** Device pixels, thanks to {@link Win32#setProcessDpiAware} plus {@code -Dsun.java2d.uiScale=1}. */
    private static Dimension screenSize() {
        return Toolkit.getDefaultToolkit().getScreenSize();
    }

    /** The half of a failure report that only the composition root can see. */
    private static String environment(String gameProcess, String dpi) {
        Dimension display = screenSize();
        return String.format(Locale.ROOT,
                "game:     %s (focused: %s)%n"
                + "display:  %dx%d, dpi awareness %s, awt scale %.2f%n"
                + "window:   %s%n",
                gameProcess, Win32.foregroundProcessName(),
                display.width, display.height, dpi, awtScale(),
                Win32.foregroundClientRect().map(Object::toString).orElse("could not be measured"));
    }

    /**
     * The {@code --diagnose <png>} argument, if that is what this invocation is: the first non-flag
     * token after {@code --diagnose}, so {@code --diagnose --reader=lattice frame.png} finds the frame
     * rather than the flag between them.
     */
    static Optional<Path> diagnoseArg(String[] args) {
        int i = Arrays.asList(args).indexOf("--diagnose");
        if (i < 0) {
            return Optional.empty();
        }
        for (int j = i + 1; j < args.length; j++) {
            if (!args[j].startsWith("--")) {
                return Optional.of(Path.of(args[j]));
            }
        }
        return Optional.empty();
    }

    /**
     * Replays a saved frame through the reader and prints what it made of it - the offline half of
     * a bug report, and the only way to tell a reader fault from a viewport fault after the fact.
     *
     * <p>The frame is read as the game's whole view, which is what {@code dumpFrame} writes. So a
     * capture that the live tool could not read, but that reads correctly here, proves the pixels
     * were always fine and the live <b>viewport</b> was wrong.
     */
    /** Replays through the default (calibrated) reader. */
    static boolean diagnose(Path png) {
        return diagnose(png, DEFAULT_READER);
    }

    static boolean diagnose(Path png, String readerKind) {
        BufferedImage img;
        try {
            img = ImageIO.read(png.toFile());
        } catch (IOException e) {
            System.out.println("Could not read " + png + ": " + e.getMessage());
            return false;
        }
        if (img == null) {
            System.out.println("Not an image: " + png);
            return false;
        }
        Viewport viewport = new Viewport(img.getWidth(), img.getHeight());
        LockAnalyzer reader = analyzer(readerKind, viewport, Tone.estimate(img, viewport));
        System.out.println("=== " + png + " ===");
        System.out.printf(Locale.ROOT, "viewport: %s (scale %.4f from the 4K calibration)%n%n",
                viewport, viewport.scale());
        System.out.println(reader.describe(img));

        int n = reader.detectPlateCount(img);
        if (n < LockModel.MIN_PLATES) {
            return false;
        }
        System.out.println("offsets:  " + offsets(reader.readState(img, n))
                + "  (0 = centered, + = left of centre, ? = row unreadable)");
        return true;
    }

    /** A read state, with the unreadable rows spelled out rather than left as {@code MIN_VALUE}. */
    private static String offsets(int[] state) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < state.length; i++) {
            out.append(i > 0 ? ", " : "")
                    .append(state[i] == LockReader.UNKNOWN ? "?" : String.valueOf(state[i]));
        }
        return out.append(']').toString();
    }

    /**
     * The executable that must own the focused window before a single key is sent, matched
     * case-insensitively against the file name: the first CLI argument that is not a {@code --flag},
     * else {@code -Dgame.process=...}, else the game's own.
     *
     * <p>The gate keys on the <b>process</b>, not the window title. Titles are not safe: a Chrome tab
     * reading "BP Mod Loader and Console Enabler at Gothic 1 Remake Nexus" contains any sensible title
     * substring, and focusing it would have typed {@code W/A/S/D} into the browser. This gate is the
     * only thing keeping stray keys out of other windows.
     */
    static String resolveGameProcess(String[] args) {
        return Arrays.stream(args)
                .filter(a -> !a.isBlank() && !a.startsWith("--"))
                .findFirst()
                .orElseGet(() -> System.getProperty("game.process", DEFAULT_GAME_PROCESS));
    }

    /**
     * Which reader to build: {@code --reader=lattice|legacy}, else {@code -Dlockpick.reader=...}, else
     * the calibrated default. It <b>must</b> start with {@code --}, or {@link #resolveGameProcess} would
     * swallow it as the game's process name (the first non-flag positional wins there).
     */
    static String resolveReader(String[] args) {
        String prefix = "--reader=";
        return Arrays.stream(args)
                .filter(a -> a.startsWith(prefix))
                .map(a -> a.substring(prefix.length()))
                .findFirst()
                .orElseGet(() -> System.getProperty("lockpick.reader", DEFAULT_READER))
                .toLowerCase(Locale.ROOT);
    }

    /**
     * The reader named by {@code kind}: only an explicit {@code legacy} selects the old pixel-calibrated
     * {@link LockReader}; anything else (the default, and any typo) gets the relative {@link LatticeReader}.
     * Both take the same {@link Tone} - the lattice reader uses it only where it can be trusted, the
     * legacy one always.
     */
    static LockAnalyzer analyzer(String kind, Viewport viewport, Tone tone) {
        return "legacy".equals(kind)
                ? new LockReader(viewport, tone)
                : new LatticeReader(viewport, tone);
    }

    /** True when {@code --dump} asks for frames instead of a solve. */
    static boolean dumpMode(String[] args) {
        return Arrays.asList(args).contains("--dump");
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
        if (!focused(foreground, gameProcess)) {
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

    /**
     * The focus gate, shared by every F8 body: true only while the game owns the focused window.
     * Says who does own it otherwise, because "F8 did nothing" is the least useful bug report there
     * is.
     */
    private static boolean focused(Supplier<String> foreground, String gameProcess) {
        String focused = foreground.get();
        if (focused.equalsIgnoreCase(gameProcess)) {
            return true;
        }
        System.out.println("[F8] Ignored: the focused window belongs to "
                + (focused.isEmpty() ? "an unknown process" : focused)
                + ", not " + gameProcess + ".");
        return false;
    }

    /**
     * {@code --dump}: F8 saves the game's view and the sidecar, and does nothing else - no probing,
     * no keys, no lockpicks spent. It is the shape a bug report should arrive in, and it is what the
     * gamma calibration frames were captured with: the lock can be walked through a known key
     * protocol by hand, one dump per step, without the tool ever touching it.
     */
    static boolean dump(Supplier<String> foreground, String gameProcess, LockView view) {
        if (!focused(foreground, gameProcess)) {
            return false;
        }
        System.out.println("[F8] Dumping the game's view...");
        view.dumpFrame("dump");
        return true;
    }

    static void printBanner(String gameProcess, String dpi) {
        System.out.println("=== The Colony's Skeleton Key ===");
        System.out.println("Nothing to configure: broken picks are read off the lockpick counter, so "
                + "the tool works at any lockpicking skill and notices if you train it.");
        System.out.println("Play at any resolution, windowed or borderless: each F8 measures the "
                + "game's window (DPI awareness: " + dpi + ") and scales the 4K calibration onto it. "
                + "Keys are sent only while " + gameProcess + " owns the focused window.");
        System.out.println("Open a lock in-game, keep it focused, and press F8.");
        System.out.println("It learns the plate connections and opens the lock, without ever resetting.");
        System.out.println("Each press starts from scratch. Quit with Ctrl-C.");
        System.out.println("NOTE: the hotkey is observed, not swallowed - the game receives F8 too.");
        System.out.println("Check F8 is unbound in the game's controls menu before you start.");
        System.out.println("Reads " + LockModel.MIN_PLATES + "-" + LockModel.MAX_PLATES + " plate locks "
                + "from the lock's own contrast, so it holds up at any gamma, in HDR, and at any size - "
                + "every one verified against labelled frames from the real game.");
        System.out.println("=================================");
    }
}
