package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Reads a lock out of one frame of the game - the seam between the two readers and everything above
 * them. {@link LivePoller} and {@link LiveLockView} depend on this, not on a concrete reader, so the
 * tool can be pointed at either without a line changing above the vision package.
 *
 * <p>There are two implementations, and they disagree about exactly one thing: <b>photometry</b>.
 * {@link LatticeReader} - the <b>shipped default</b> - reads the lock with ratios measured off the
 * frame itself, so it holds up across a wide gamma range, in HDR frames a {@code Tone} curve cannot
 * express, and at small resolutions; it matches the reference on every labelled frame and reads HDR
 * ones the reference cannot. {@link LockReader} reads the lock with absolute pixel values fitted on
 * one screen at one gamma, undone on the way in by {@link Tone}; it is the pixel-calibrated reference
 * and corpus gate, kept behind {@code --reader=legacy}. Both share the measured geometry
 * ({@link FanGeometry}); neither knows the other exists.
 *
 * <p>The contract every implementation owes its callers:
 * <ul>
 *   <li>{@link #detectPlateCount} returns 4..7, or <b>-1</b> for no lock. A wrong <i>smaller</i> count
 *       is worse than -1 - it hands the session a model with too few plates and drives them into walls
 *       - so a reader that is unsure must return -1, never guess low.</li>
 *   <li>{@link #readState} entries are in {@code [-MAX_OFFSET, +MAX_OFFSET]} or
 *       {@link LockModel#UNKNOWN}, and {@code UNKNOWN} must mean "refused", never "guessed": the
 *       session's whole occlusion machinery leans on that.</li>
 * </ul>
 */
public interface LockAnalyzer {

    /** Plate count of the lock on screen (4..7), or -1 if no supported lock is visible. */
    int detectPlateCount(BufferedImage img);

    /** Each plate's offset, in {@code [-3, +3]} or {@link LockModel#UNKNOWN}, for an {@code n}-plate lock. */
    int[] readState(BufferedImage img, int n);

    /** The reader's own account of a frame - the first thing to read on a "no lock detected" report. */
    String describe(BufferedImage img);
}
