package io.github.markosa84.colonysskeletonkey;

import java.awt.Dimension;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markosa84.colonysskeletonkey.control.KeySender;
import io.github.markosa84.colonysskeletonkey.control.LockPoller;
import io.github.markosa84.colonysskeletonkey.control.RobotKeyboard;
import io.github.markosa84.colonysskeletonkey.control.Slider;
import io.github.markosa84.colonysskeletonkey.control.Telemetry;
import io.github.markosa84.colonysskeletonkey.solver.Move;
import io.github.markosa84.colonysskeletonkey.vision.TestFrames;
import io.github.markosa84.colonysskeletonkey.vision.LatticeReader;
import io.github.markosa84.colonysskeletonkey.vision.LockReader;
import io.github.markosa84.colonysskeletonkey.vision.Tone;
import io.github.markosa84.colonysskeletonkey.vision.Viewport;
import io.github.markosa84.colonysskeletonkey.win32.Win32;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console app around the session: the hotkey, the focus gate and the report. {@code main} itself
 * stays out of reach - it owns a {@code Robot}, a {@code Toolkit} and an endless loop - but
 * everything it decides is lifted out of it and testable here.
 *
 * <p>The focus gate is the one that matters. {@code GetAsyncKeyState} observes F8 globally, so the
 * press arrives whatever is on screen; without this check an F8 pressed over a browser would drive
 * a whole lockpicking session into the browser.
 */
class AutoLockpickTest {

    private static final String GAME = "G1R-Win64-Shipping.exe";

    // -- the hotkey ------------------------------------------------------------------------------

    /** A held key is one press, not one per poll: 30ms polls would otherwise fire ~33 sessions/s. */
    @Test
    void theHotkeyFiresOnceWhenPressedAndNotAgainWhileHeld() {
        boolean[] down = {false};
        AutoLockpick.Hotkey f8 = new AutoLockpick.Hotkey(Win32.VK_F8, vk -> down[0]);

        assertFalse(f8.pressed(), "not pressed yet");
        down[0] = true;
        assertTrue(f8.pressed(), "the poll where it goes from up to down");
        assertFalse(f8.pressed(), "still held: the edge is gone");
        assertFalse(f8.pressed());
        down[0] = false;
        assertFalse(f8.pressed(), "releasing is not a press");
        down[0] = true;
        assertTrue(f8.pressed(), "pressed again: a new edge");
    }

    @Test
    void theHotkeyWatchesTheKeyItWasGiven() {
        List<Integer> polled = new ArrayList<>();
        IntPredicate spy = vk -> {
            polled.add(vk);
            return false;
        };

        new AutoLockpick.Hotkey(Win32.VK_F8, spy).pressed();

        assertEquals(List.of(Win32.VK_F8), polled);
        assertEquals(0x77, Win32.VK_F8, "F8 - the one hotkey the game does not already use");
    }

    // -- which process may receive keys ------------------------------------------------------------

    @Test
    void theGameProcessDefaultsToTheGameAndYieldsToAnArgument() {
        assertEquals(GAME, AutoLockpick.resolveGameProcess(new String[] {}));
        assertEquals("other.exe", AutoLockpick.resolveGameProcess(new String[] {"other.exe"}));
        assertEquals(GAME, AutoLockpick.resolveGameProcess(new String[] {"  "}),
                "a blank argument is not a process name");
    }

    /**
     * A flag is not a process name. {@code --dump} used to arrive as {@code args[0]} and be taken
     * for the executable to gate on, which would have turned the focus gate off against every
     * window on the machine.
     */
    @Test
    void aFlagIsNotMistakenForTheProcessName() {
        assertEquals(GAME, AutoLockpick.resolveGameProcess(new String[] {"--dump"}));
        assertEquals("other.exe",
                AutoLockpick.resolveGameProcess(new String[] {"--dump", "other.exe"}));
    }

    // -- --reader ----------------------------------------------------------------------------------

    /** The reader defaults to the relative one, and {@code --reader=legacy} names the old one. */
    @Test
    void theReaderDefaultsToLatticeAndYieldsToTheFlag() {
        assertEquals("lattice", AutoLockpick.resolveReader(new String[] {}));
        assertEquals("legacy", AutoLockpick.resolveReader(new String[] {"--reader=legacy"}));
        assertEquals("lattice", AutoLockpick.resolveReader(new String[] {"--reader=lattice"}));
        // The flag starts with --, so the game-process resolver must leave it alone rather than take
        // it for the executable to gate on.
        assertEquals(GAME, AutoLockpick.resolveGameProcess(new String[] {"--reader=legacy"}));
    }

    /** Only an explicit {@code legacy} builds the old reader; the default and any typo build the new one. */
    @Test
    void theAnalyzerFactoryPicksTheNamedReader() {
        Viewport viewport = Viewport.REFERENCE;
        assertTrue(AutoLockpick.analyzer("lattice", viewport, Tone.CALIBRATED) instanceof LatticeReader);
        assertTrue(AutoLockpick.analyzer("legacy", viewport, Tone.CALIBRATED) instanceof LockReader);
        assertTrue(AutoLockpick.analyzer("anything-else", viewport, Tone.CALIBRATED) instanceof LatticeReader,
                "an unknown reader name falls back to the default reader, never to nothing");
    }

    // -- --dump ------------------------------------------------------------------------------------

    @Test
    void dumpModeIsRecognisedOnlyWhenAskedFor() {
        assertTrue(AutoLockpick.dumpMode(new String[] {"--dump"}));
        assertTrue(AutoLockpick.dumpMode(new String[] {"other.exe", "--dump"}));
        assertFalse(AutoLockpick.dumpMode(new String[] {}));
        assertFalse(AutoLockpick.dumpMode(new String[] {"other.exe"}));
    }

    /** A dump writes the frame and touches nothing else: no plate is read, no key is sent. */
    @Test
    void aDumpOverTheFocusedGameSavesTheFrameAndProbesNothing() {
        RecordingView view = new RecordingView();

        String log = Stdout.capturing(
                () -> assertTrue(AutoLockpick.dump(() -> GAME, GAME, view)));

        assertEquals(List.of("dump"), view.dumped);
        assertEquals(0, view.probes, "--dump must not read the lock, only photograph it");
        assertTrue(log.contains("Dumping"), log);
    }

    /** The focus gate covers the dump too: a stray F8 must not photograph someone's desktop. */
    @Test
    void aDumpOverAnotherWindowSavesNothing() {
        RecordingView view = new RecordingView();

        String log = Stdout.capturing(
                () -> assertFalse(AutoLockpick.dump(() -> "chrome.exe", GAME, view)));

        assertTrue(view.dumped.isEmpty(), "the game was not focused, so there is nothing to dump");
        assertTrue(log.contains("Ignored"), log);
    }

    /** A {@link LockView} that only remembers what it was asked to do. */
    private static final class RecordingView implements io.github.markosa84.colonysskeletonkey
            .session.LockView {
        private final List<String> dumped = new ArrayList<>();
        private int probes;

        @Override
        public int detectPlateCount() {
            probes++;
            return 5;
        }

        @Override
        public void dumpFrame(String tag) {
            dumped.add(tag);
        }
    }

    // -- the focus gate --------------------------------------------------------------------------

    /**
     * The gate keys on the process, never the window title: a Chrome tab titled "gothic 1 remake
     * lockpicking levels - Google Search" passes any sensible title test, and focusing it would have
     * typed W/A/S/D into the browser.
     */
    @Test
    void anF8OverAnotherWindowRunsNothingAtAll() {
        boolean[] ran = {false};

        String log = Stdout.capturing(() -> {
            boolean started = AutoLockpick.run(new Telemetry(), () -> "chrome.exe", GAME,
                    () -> ran[0] = true);
            assertFalse(started);
        });

        assertFalse(ran[0], "not one key may be sent into a window that is not the game's");
        assertTrue(log.contains("Ignored"), log);
        assertTrue(log.contains("chrome.exe"), log);
    }

    /** A foreground process we cannot even name is not the game either. Deny by default. */
    @Test
    void anUnidentifiableForegroundWindowIsAlsoRefused() {
        String log = Stdout.capturing(() -> AutoLockpick.run(new Telemetry(), () -> "", GAME,
                () -> fail("the session must not start")));

        assertTrue(log.contains("an unknown process"), log);
    }

    @Test
    void theProcessMatchIsCaseInsensitive() {
        boolean[] ran = {false};

        Stdout.capturing(() -> AutoLockpick.run(new Telemetry(), () -> GAME.toUpperCase(), GAME,
                () -> ran[0] = true));

        assertTrue(ran[0]);
    }

    /** A run over the focused game reports what it did, and how its wall clock was spent. */
    @Test
    void aRunOverTheFocusedGamePrintsItsBreakdown() {
        Telemetry telemetry = new Telemetry();

        String log = Stdout.capturing(() -> {
            boolean started = AutoLockpick.run(telemetry, () -> GAME, GAME, () -> slide(telemetry));
            assertTrue(started);
        });

        assertTrue(log.contains("[F8] Learning the open lock"), log);
        assertTrue(log.contains("[F8] Done in"), log);
        assertTrue(log.contains("1 slides"), "the telemetry breakdown must follow the run: " + log);
    }

    /** Each press reports its own run, so the previous one's numbers must not leak into it. */
    @Test
    void everyPressStartsItsTelemetryFromScratch() {
        Telemetry telemetry = new Telemetry();
        slide(telemetry);
        slide(telemetry);

        String log = Stdout.capturing(() ->
                AutoLockpick.run(telemetry, () -> GAME, GAME, () -> slide(telemetry)));

        assertTrue(log.contains("1 slides"), log);
        assertFalse(log.contains("3 slides"), log);
    }

    /**
     * The player alt-tabbed mid-session. Every key tap re-checks the focus and throws, and the app
     * must land on its feet: say so, and go back to waiting for F8.
     */
    @Test
    void aFocusLostMidRunAbortsCleanlyInsteadOfCrashing() {
        String log = Stdout.capturing(() -> {
            boolean started = AutoLockpick.run(new Telemetry(), () -> GAME, GAME, () -> {
                throw new RobotKeyboard.FocusLost("the game lost the foreground mid-run");
            });
            assertTrue(started, "it did start - it just could not finish");
        });

        assertTrue(log.contains("[F8] Aborted"), log);
        assertTrue(log.contains("Refocus the game and press F8 again"), log);
    }

    // -- which rectangle the game is drawing into --------------------------------------------------

    /**
     * The bug this whole seam exists for. The tool measured the <b>display</b> and assumed the game
     * filled it; a player running the game at less than their desktop resolution therefore had the
     * reader scanning a rectangle the lock was not in, and got "no lock detected" over a screenshot
     * that looked perfectly fine. Measure the window.
     */
    @Test
    void aGameSmallerThanTheDesktopIsMeasuredByItsOwnWindow() {
        Viewport vp = AutoLockpick.resolveViewport(
                Optional.of(new Win32.Rect(640, 360, 2560, 1440)), new Dimension(3840, 2160));

        assertEquals(new Viewport(640, 360, 2560, 1440), vp);
        assertEquals(2560.0 / 3840, vp.scale(), 1e-9, "scaled to the game's view, not the desktop");
    }

    /** A game on the second monitor starts nowhere near the desktop's origin. */
    @Test
    void aWindowOnAnotherMonitorKeepsItsOrigin() {
        Viewport vp = AutoLockpick.resolveViewport(
                Optional.of(new Win32.Rect(3840, -120, 1920, 1080)), new Dimension(3840, 2160));

        assertEquals(3840, vp.originX());
        assertEquals(-120, vp.originY(), "monitors above the primary have negative y");
    }

    @Test
    void aGameFillingTheDisplayIsExactlyWhatItAlwaysWas() {
        Viewport vp = AutoLockpick.resolveViewport(
                Optional.of(new Win32.Rect(0, 0, 3840, 2160)), new Dimension(3840, 2160));

        assertEquals(Viewport.REFERENCE, vp);
    }

    /**
     * No window, or one too small to be a game: fall back to the display, which is what the tool
     * did for its whole first year. A splash screen owning the foreground must not make the reader
     * scan a 300px box.
     */
    @Test
    void anImplausibleWindowFallsBackToTheDisplay() {
        Dimension display = new Dimension(2560, 1440);

        assertEquals(new Viewport(2560, 1440),
                AutoLockpick.resolveViewport(Optional.empty(), display));
        assertEquals(new Viewport(2560, 1440), AutoLockpick.resolveViewport(
                Optional.of(new Win32.Rect(100, 100, 300, 200)), display), "a splash, not a game");
    }

    /**
     * Below the read floor the lock renders too small - a centred plate's raised pin merges with a
     * hole and the walk drops to 5/6 - so the tool refuses to solve rather than miscount silently. The
     * floor is an aspect-fit scale (1280x720 is 1/3 of 4K), so it tracks the rendered lock rather than
     * the frame's width: 1280x720 is in, the four dropped sweep modes are out.
     */
    @Test
    void aViewSmallerThanTheReadFloorIsRefused() {
        assertFalse(AutoLockpick.tooSmall(new Viewport(1280, 720)), "1280x720 is the floor, supported");
        assertFalse(AutoLockpick.tooSmall(new Viewport(1280, 1024)));
        assertFalse(AutoLockpick.tooSmall(Viewport.REFERENCE), "4K is comfortably supported");
        assertTrue(AutoLockpick.tooSmall(new Viewport(1176, 664)), "the largest dropped mode");
        assertTrue(AutoLockpick.tooSmall(new Viewport(1024, 768)));
        assertTrue(AutoLockpick.tooSmall(new Viewport(800, 600)));
    }

    // -- the DPI self-check ------------------------------------------------------------------------

    /**
     * Nothing downstream can catch this one: with uiScale != 1 the Robot still returns an image of
     * exactly the size asked for, only resampled from the wrong region, so every size check in the
     * pipeline passes and the reader just quietly finds nothing.
     */
    @Test
    void javaScalingTheScreenIsCalledOutByName() {
        assertEquals("", AutoLockpick.dpiWarning(1.0), "uiScale=1 took: nothing to say");

        String warning = AutoLockpick.dpiWarning(1.5);
        assertTrue(warning.contains("1.50x"), warning);
        assertTrue(warning.contains("-Dsun.java2d.uiScale=1"), warning);
        assertTrue(warning.contains("lockpick.bat"), "it must say how to fix it: " + warning);
    }

    // -- the banner ------------------------------------------------------------------------------

    @Test
    void theBannerSaysWhereItWillLookAndWhatItWillType() {
        String banner = Stdout.capturing(() -> AutoLockpick.printBanner("other.exe", "system"));

        assertTrue(banner.contains("any resolution"), banner);
        assertTrue(banner.contains("system"), "the DPI awareness obtained belongs in a bug report");
        assertTrue(banner.contains("other.exe"),
                "the banner must name the process the gate waits for: " + banner);
        assertTrue(banner.contains("F8"), banner);
        assertTrue(banner.contains(AutoLockpick.version()), "the build belongs in every report: " + banner);
    }

    // -- the run log header ------------------------------------------------------------------------

    /** Run from classes with no jar manifest, the version is "dev" rather than null or a crash. */
    @Test
    void theVersionIsDevWhenThereIsNoManifestToReadItFrom() {
        assertEquals("dev", AutoLockpick.version());
    }

    /**
     * The head of every run log: the console gets the build and the reader (so a pasted snippet is
     * still identifiable), and the file gets the full environment, the viewport scale and the reader's
     * account of the frame - the diagnostics that used to appear only when a failure dumped a frame.
     */
    @Test
    void theHeaderPutsTheBuildOnTheConsoleAndTheDiagnosticsInTheFile() throws Exception {
        java.io.ByteArrayOutputStream detailBytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream detail = new java.io.PrintStream(detailBytes, true, "UTF-8");

        String console = Stdout.capturing(() -> AutoLockpick.solveHeader("1.2.3", "lattice",
                Viewport.REFERENCE, Tone.CALIBRATED, "display: 3840x2160\n", "found 5 warm blobs",
                detail));

        assertTrue(console.contains("1.2.3") && console.contains("lattice"), console);
        assertFalse(console.contains("found 5 warm blobs"), "the blob account is file-only: " + console);
        String file = detailBytes.toString("UTF-8");
        assertTrue(file.contains("display: 3840x2160"), file);
        assertTrue(file.contains("scale"), "the viewport scale is a bug report's first suspect: " + file);
        assertTrue(file.contains("found 5 warm blobs"), file);
    }

    /** With no file to write to (the fallback), the header is the console lines and nothing throws. */
    @Test
    void theHeaderWithoutAFileIsJustTheConsoleLines() {
        String console = Stdout.capturing(() -> AutoLockpick.solveHeader("dev", "legacy",
                Viewport.REFERENCE, Tone.CALIBRATED, "unused", "unused", null));

        assertTrue(console.contains("dev") && console.contains("legacy"), console);
    }

    /** The reader's account goes into the report; a reader that throws costs a line, not the run. */
    @Test
    void describeNeverThrowsIntoTheReport() {
        java.awt.image.BufferedImage frame =
                new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_RGB);

        assertEquals("all good", AutoLockpick.describeOrWhyNot(new StubAnalyzer("all good"), frame));
        assertTrue(AutoLockpick.describeOrWhyNot(new StubAnalyzer(null), frame)
                .startsWith("reader.describe failed:"), "a throwing reader is caught, not propagated");
    }

    /** A focused, logged run keeps its file and says where it went, so the reporter can attach it. */
    @Test
    void aLoggedRunOverTheGameKeepsItsFileAndSaysWhere(@TempDir Path dir) throws Exception {
        Path logFile = dir.resolve("captures").resolve("f8.log");
        java.io.ByteArrayOutputStream consoleBytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream console = new java.io.PrintStream(consoleBytes, true, "UTF-8");
        RunLog log = RunLog.open(logFile, console);

        AutoLockpick.solveLogged(log, console, new Telemetry(), () -> GAME, GAME,
                () -> System.out.println("the session ran"));

        assertTrue(java.nio.file.Files.readString(logFile).contains("the session ran"),
                "the file must carry what the run printed");
        assertTrue(consoleBytes.toString("UTF-8").contains("full log saved to"),
                consoleBytes.toString("UTF-8"));
    }

    /** An F8 over another window logs nothing: the file is deleted and no path is announced. */
    @Test
    void aLoggedRunOverAnotherWindowLeavesNoFile(@TempDir Path dir) throws Exception {
        Path logFile = dir.resolve("f8.log");
        java.io.ByteArrayOutputStream consoleBytes = new java.io.ByteArrayOutputStream();
        java.io.PrintStream console = new java.io.PrintStream(consoleBytes, true, "UTF-8");
        RunLog log = RunLog.open(logFile, console);

        AutoLockpick.solveLogged(log, console, new Telemetry(), () -> "chrome.exe", GAME,
                () -> fail("the session must not run when the game is not focused"));

        assertFalse(java.nio.file.Files.exists(logFile), "an ignored press leaves no log");
        assertFalse(consoleBytes.toString("UTF-8").contains("full log saved"),
                consoleBytes.toString("UTF-8"));
        assertTrue(consoleBytes.toString("UTF-8").contains("Ignored"), consoleBytes.toString("UTF-8"));
    }

    /** A reader that only answers {@code describe}, with the text it was given (null = throw). */
    private record StubAnalyzer(String describe) implements io.github.markosa84.colonysskeletonkey
            .vision.LockAnalyzer {
        @Override
        public int detectPlateCount(java.awt.image.BufferedImage img) {
            return -1;
        }

        @Override
        public int[] readState(java.awt.image.BufferedImage img, int n) {
            return new int[n];
        }

        @Override
        public String describe(java.awt.image.BufferedImage img) {
            if (describe == null) {
                throw new IllegalStateException("boom");
            }
            return describe;
        }
    }

    // -- replaying a reported frame ----------------------------------------------------------------

    @Test
    void diagnoseIsRecognisedOnlyWithAFrameToReplay() {
        assertEquals(Optional.of(Path.of("f.png")),
                AutoLockpick.diagnoseArg(new String[] {"--diagnose", "f.png"}));
        assertEquals(Optional.empty(), AutoLockpick.diagnoseArg(new String[] {"--diagnose"}),
                "no frame named: not a diagnose run");
        assertEquals(Optional.empty(), AutoLockpick.diagnoseArg(new String[] {"other.exe"}));
        // A flag between --diagnose and the frame must be stepped over, not taken for the frame.
        assertEquals(Optional.of(Path.of("f.png")),
                AutoLockpick.diagnoseArg(new String[] {"--diagnose", "--reader=lattice", "f.png"}));
        assertEquals(Optional.empty(),
                AutoLockpick.diagnoseArg(new String[] {"--diagnose", "--reader=lattice"}),
                "a flag after --diagnose with no frame is still not a diagnose run");
    }

    /**
     * The offline half of a bug report: the reporter's own capture, read back through the reader.
     * This is a 1440p frame - the resolution the tool was reported broken at - and it reads
     * perfectly, which is the whole point: the pixels were never the problem.
     */
    @Test
    void diagnoseReplaysASavedFrameAndReadsTheLock() {
        String log = Stdout.capturing(() ->
                assertTrue(AutoLockpick.diagnose(TestFrames.path("2560x1440/front-plate-sweep/step-0.png"))));

        assertTrue(log.contains("2560x1440+0+0"), log);
        assertTrue(log.contains("5 plates"), log);
        assertTrue(log.contains("[3, 1, 2, 0, 3]"), "the offsets the sweep labels: " + log);
    }

    /** A frame with no lock in it says so, and names the likely cause. */
    @Test
    void diagnoseOnAFrameWithNoLockExplainsItself(@TempDir Path dir) throws Exception {
        Path blank = dir.resolve("blank.png");
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(3840, 2160,
                java.awt.image.BufferedImage.TYPE_INT_RGB), "png", blank.toFile());

        String log = Stdout.capturing(() -> assertFalse(AutoLockpick.diagnose(blank)));

        assertTrue(log.contains("No lock"), log);
        assertTrue(log.contains("viewport is wrong"), "it must name the likely cause: " + log);
    }

    @Test
    void diagnoseOnSomethingThatIsNotAFrameSaysSo(@TempDir Path dir) throws Exception {
        Path notAnImage = java.nio.file.Files.writeString(dir.resolve("notes.txt"), "hello");

        assertFalse(Stdout.capturing(() -> assertFalse(AutoLockpick.diagnose(notAnImage))).isEmpty());
        assertFalse(Stdout.capturing(() ->
                assertFalse(AutoLockpick.diagnose(dir.resolve("nope.png")))).isEmpty());
    }

    /**
     * One slide's worth of telemetry, so a run has something to report. Telemetry's counters are
     * package-private on purpose - only the {@link Slider} may write them - so the honest way to
     * produce some from out here is to slide a plate.
     */
    private static void slide(Telemetry telemetry) {
        LockPoller stillLock = new LockPoller() {
            @Override
            public int[] readLock(int n) {
                return new int[] {0};
            }

            @Override
            public long pickFingerprint() {
                return 1L;
            }
        };
        Slider slider = new Slider(stillLock, new KeySender(vk -> {}, 0),
                new Slider.Timing(0, 0, 100, 0, 2, 10), telemetry);

        slider.play(1, new int[] {0}, new Move(0, +1));
    }

    private static void fail(String message) {
        throw new AssertionError(message);
    }
}
