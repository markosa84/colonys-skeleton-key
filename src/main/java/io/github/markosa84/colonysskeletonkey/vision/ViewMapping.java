package io.github.markosa84.colonysskeletonkey.vision;

/**
 * How a calibrated 4K reference coordinate lands on the frame in hand: a scale and an offset, and
 * nothing else.
 *
 * <p>{@link Viewport} computes exactly this, and computed it alone for a year - the whole vision
 * layer asked the viewport where things were, and the viewport worked it out from the game window's
 * measured rectangle. That is one way to know where the lock is, and it has one failure mode the
 * tool cannot see: measure the wrong rectangle and every coordinate is wrong together, silently
 * (see {@link Viewport}'s note, and {@code WindowedGameTest}). The other way is to find the lock's
 * own hole lattice in the pixels and solve for where it must be - and what <i>that</i> produces is
 * not a window rectangle at all. It is this.
 *
 * <p>So the readers take the answer rather than the question: a reader needs to know where the lock
 * lands, and has no business knowing how that was worked out. {@code (scale, ox, oy)} is what the
 * viewport's aspect-fit reduces to:
 *
 * <pre>
 *   scale = min(width / 3840, height / 2160)
 *   ox    = width / 2 - 1920 * scale        (positions are anchored to the view's centre)
 *   oy    = height / 2 - 1080 * scale
 * </pre>
 *
 * and at {@link Viewport#REFERENCE} it is exactly {@link #IDENTITY}, which is what lets the 4K
 * calibration gate go on certifying this parameterization unchanged.
 *
 * <p><b>View-local, like everything else in this package.</b> A mapping says where the lock is
 * <i>in the image</i>; the game window's origin on the virtual desktop is capture metadata and stays
 * in {@link Viewport}, where only {@link GameScreen} ever reads it.
 */
public record ViewMapping(double scale, double ox, double oy) {

    /** Reference pixels, unmoved and unscaled: what a 4K frame needs, and what the fixtures get. */
    public static final ViewMapping IDENTITY = new ViewMapping(1, 0, 0);

    public ViewMapping {
        if (!(scale > 0) || !Double.isFinite(ox) || !Double.isFinite(oy)) {
            throw new IllegalArgumentException(
                    "not a mapping: scale " + scale + " at (" + ox + ", " + oy + ")");
        }
    }

    /** A reference x-position, mapped onto this view. */
    public double x(double refX) {
        return ox + refX * scale;
    }

    /** A reference y-position, mapped onto this view. */
    public double y(double refY) {
        return oy + refY * scale;
    }

    /** A reference length, distance or offset, mapped onto this view. */
    public double len(double refLength) {
        return refLength * scale;
    }

    /** A reference blob area, mapped onto this view - quadratically: a blob is a pixel count. */
    public double area(double refArea) {
        return refArea * scale * scale;
    }
}
