package io.github.markosa84.colonysskeletonkey.vision;

import io.github.markosa84.colonysskeletonkey.session.LockView;

/**
 * The live {@link LockView}: full-screen captures decoded by {@link LockReader}, and failure
 * frames dumped through {@link Captures} for offline replay.
 */
public final class LiveLockView implements LockView {

    private final GameScreen screen;
    private final LockReader reader;

    public LiveLockView(GameScreen screen, LockReader reader) {
        this.screen = screen;
        this.reader = reader;
    }

    @Override
    public int detectPlateCount() {
        return reader.detectPlateCount(screen.capture());
    }

    @Override
    public boolean[] readCentered(int n) {
        return reader.readCentered(screen.capture(), n);
    }

    @Override
    public void dumpFrame(String tag) {
        Captures.save(screen.capture(), tag);
    }
}
