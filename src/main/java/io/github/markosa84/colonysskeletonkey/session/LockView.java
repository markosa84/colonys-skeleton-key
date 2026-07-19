package io.github.markosa84.colonysskeletonkey.session;

/**
 * What the session can see of the lock without moving anything: single-frame reads of the live
 * screen, plus a way to save the evidence when a read goes wrong.
 */
public interface LockView {

    /** Plate count of the lock on screen, or -1 if no supported lock is visible. */
    int detectPlateCount();

    /** Saves the current frame to disk, tagged, so a misread can be replayed offline. */
    void dumpFrame(String tag);
}
