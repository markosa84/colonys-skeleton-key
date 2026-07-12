package io.github.markosa84.colonysskeletonkey.control;

/**
 * One sample of the live lock, for {@link Slider}'s polling. Capturing and decoding the frame is
 * the implementation's business; this contract is only "the offsets, or null".
 */
public interface LockPoller {

    /**
     * Reads every plate's offset from the screen. A plate that cannot be read right now - because
     * it is mid-slide, or because the reader cannot resolve its row in this frame and refuses to
     * guess - reads
     * {@link io.github.markosa84.colonysskeletonkey.solver.LockModel#UNKNOWN}. A transiently
     * unreadable plate means "still moving"; a <i>persistently</i> unreadable one is settled with
     * an unresolved row, and it is {@link Slider}'s job to tell the two apart.
     */
    int[] readLock(int n);

    /**
     * A fingerprint of the remaining-lockpicks counter. Two calls returning different values mean
     * the number changed, i.e. <b>a pick broke</b> - the only signal a break gives above skill
     * level 0, where the puzzle is left exactly as it was.
     */
    long pickFingerprint();
}
