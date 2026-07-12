package io.github.markosa84.colonysskeletonkey.session;

/**
 * The selected-plate cursor, as far as the session needs it. Nothing on screen shows which plate
 * is selected, so the position is tracked rather than read - and the only way to make it certain
 * again is to saturate the selection into its clamp.
 */
public interface CursorKeys {

    /**
     * Forces the selection onto the last plate ({@code n - 1}) by saturating presses into the
     * clamp. Call whenever the game may have moved the selection without us - a break, a reset -
     * because certainty costs only ~10ms per press.
     */
    void endCursor(int n);

    /** The plate index the selection is currently on. */
    int cursor();
}
