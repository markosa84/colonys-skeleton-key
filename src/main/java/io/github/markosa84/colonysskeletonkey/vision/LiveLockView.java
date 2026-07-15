package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.function.Supplier;

import io.github.markosa84.colonysskeletonkey.session.LockView;

/**
 * The live {@link LockView}: captures of the game's view decoded by {@link LockReader}, and failure
 * frames dumped through {@link Captures} for offline replay.
 *
 * <p>A dump carries a diagnostic sidecar, because the failure it usually documents - the reader
 * looking for the lock in the wrong rectangle - leaves a screenshot that looks fine. The
 * environment half of that report (which process is focused, what the desktop measures) is supplied
 * by the caller: this package sees pixels, and knowing about Win32 is not its job.
 */
public final class LiveLockView implements LockView {

    private final GameScreen screen;
    private final LockAnalyzer reader;
    private final Captures captures;
    private final Supplier<String> environment;

    public LiveLockView(GameScreen screen, LockAnalyzer reader, Supplier<String> environment) {
        this(screen, reader, new Captures(), environment);
    }

    LiveLockView(GameScreen screen, LockAnalyzer reader, Captures captures,
            Supplier<String> environment) {
        this.screen = screen;
        this.reader = reader;
        this.captures = captures;
        this.environment = environment;
    }

    @Override
    public int detectPlateCount() {
        return reader.detectPlateCount(screen.capture());
    }

    @Override
    public boolean[] readCentered(int n) {
        return reader.readCentered(screen.capture(), n);
    }

    /**
     * The <b>full</b> view grab, never {@link GameScreen#captureLock()}: every pixel outside the
     * lock box of that composite is stale, and a dump is meant to be re-readable evidence.
     */
    @Override
    public void dumpFrame(String tag) {
        BufferedImage frame = screen.capture();
        captures.save(frame, tag, notes(frame));
    }

    /**
     * What the tool believed it was reading, and what it actually found there. {@link Locale#ROOT},
     * as everything diagnostic here is: a scale of "0,6667" in a bug report is a scale nobody can
     * paste back into a test.
     *
     * <p>The environment is queried defensively. It reaches out to Win32 and to the display, and a
     * dump is written precisely when something is already wrong - so a failure there must cost the
     * report a line, not the whole report.
     */
    private String notes(BufferedImage frame) {
        String environmentOrWhyNot;
        try {
            environmentOrWhyNot = environment.get();
        } catch (RuntimeException e) {
            environmentOrWhyNot = "environment: could not be read (" + e + ")\n";
        }
        return "The Colony's Skeleton Key - diagnostics\n\n"
                + environmentOrWhyNot
                + String.format(Locale.ROOT, "viewport: %s (scale %.4f from the 4K calibration)%n%n",
                        screen.viewport(), screen.viewport().scale())
                + reader.describe(frame) + "\n";
    }
}
