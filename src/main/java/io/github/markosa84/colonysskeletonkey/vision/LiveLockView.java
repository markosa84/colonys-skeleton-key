package io.github.markosa84.colonysskeletonkey.vision;

import io.github.markosa84.colonysskeletonkey.session.LockView;

/**
 * The live {@link LockView}: full-screen captures decoded by {@link LockReader}, and failure
 * frames dumped through {@link Captures} for offline replay.
 */
public final class LiveLockView implements LockView {

    private final GameScreen screen;
    private final LockReader reader;
    private final Captures captures;

    public LiveLockView(GameScreen screen, LockReader reader) {
        this(screen, reader, new Captures());
    }

    LiveLockView(GameScreen screen, LockReader reader, Captures captures) {
        this.screen = screen;
        this.reader = reader;
        this.captures = captures;
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
     * The <b>full</b> screen grab, never {@link GameScreen#captureLock()}: every pixel outside the
     * lock box of that composite is stale, and a dump is meant to be re-readable evidence.
     */
    @Override
    public void dumpFrame(String tag) {
        captures.save(screen.capture(), tag);
    }
}
