package io.github.markosa84.colonysskeletonkey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;

import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.control.KeySender;
import io.github.markosa84.colonysskeletonkey.control.LockPoller;
import io.github.markosa84.colonysskeletonkey.control.RobotKeyboard;
import io.github.markosa84.colonysskeletonkey.control.Slider;
import io.github.markosa84.colonysskeletonkey.control.Telemetry;
import io.github.markosa84.colonysskeletonkey.solver.Move;
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

    // -- the banner ------------------------------------------------------------------------------

    @Test
    void theBannerSaysWhereItWillLookAndWhatItWillType() {
        String banner = Stdout.capturing(() ->
                AutoLockpick.printBanner(new Viewport(1920, 1080), "other.exe"));

        assertTrue(banner.contains("1920x1080"), banner);
        assertTrue(banner.contains("scaled from the 4K calibration"), banner);
        assertTrue(banner.contains("other.exe"),
                "the banner must name the process the gate waits for: " + banner);
        assertTrue(banner.contains("F8"), banner);
    }

    @Test
    void theBannerSaysWhenItIsRunningAtTheCalibratedResolution() {
        String banner = Stdout.capturing(() ->
                AutoLockpick.printBanner(Viewport.REFERENCE, GAME));

        assertTrue(banner.contains("the calibrated resolution"), banner);
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
