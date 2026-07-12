package io.github.markosa84.colonysskeletonkey.vision;

import io.github.markosa84.colonysskeletonkey.control.LockPoller;

/**
 * The live {@link LockPoller}: grab the lock's screen box, decode the offsets. Polling uses
 * {@link GameScreen#captureLock()} and never the full-screen grab, because capture - not
 * analysis - is what a sample costs.
 */
public final class LivePoller implements LockPoller {

    private final GameScreen screen;
    private final LockReader reader;

    public LivePoller(GameScreen screen, LockReader reader) {
        this.screen = screen;
        this.reader = reader;
    }

    @Override
    public int[] readLock(int n) {
        return reader.readState(screen.captureLock(), n);
    }

    @Override
    public long pickFingerprint() {
        return screen.pickCounterFingerprint();
    }
}
