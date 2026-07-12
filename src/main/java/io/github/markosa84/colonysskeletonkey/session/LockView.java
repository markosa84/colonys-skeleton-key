package io.github.markosa84.colonysskeletonkey.session;

/**
 * What the session can see of the lock without moving anything: single-frame reads of the live
 * screen, plus a way to save the evidence when a read goes wrong.
 */
public interface LockView {

    /** Plate count of the lock on screen, or -1 if no supported lock is visible. */
    int detectPlateCount();

    /**
     * Per-plate centred flag (the pin-pop signal), for a lock known to have {@code n} plates.
     * The pop is exact and needs no hole rows, so this - never hole counting - is what confirms
     * "the lock is open".
     */
    boolean[] readCentered(int n);

    /** Saves the current frame to disk, tagged, so a misread can be replayed offline. */
    void dumpFrame(String tag);
}
