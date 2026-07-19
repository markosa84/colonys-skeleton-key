package io.github.markosa84.colonysskeletonkey.vision;

/**
 * The one low-level pixel primitive the whole vision layer shares. It has no state and knows nothing
 * about the lock; it exists so that {@link GameScreen}, {@link Tone} and both readers can turn a
 * packed pixel into a luminance without any of them having to reach into one particular reader for it.
 */
final class Pixels {

    private Pixels() {
    }

    /** Rec. 601 luma of a packed ARGB pixel. */
    static int luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b);
    }
}
