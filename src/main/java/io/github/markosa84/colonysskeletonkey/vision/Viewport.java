package io.github.markosa84.colonysskeletonkey.vision;

/**
 * The screen the game renders into, and the mapping from the calibrated 4K {@link #REFERENCE}
 * coordinates onto it. Every pixel constant in the vision layer is a value <b>measured at 4K</b>;
 * this record is the one place that turns those measurements into coordinates for the screen the
 * game is actually running at.
 *
 * <p>The mapping is an <b>aspect-fit</b>, measured against the live game: the 16:9 reference view
 * is fitted inside the screen and stays centred. Wider aspects show more world at the sides
 * (Hor+, height sets the scale); narrower aspects (16:10, 4:3, 5:4) show more above and below
 * (Vert+, width sets the scale) - verified by live lock sweeps at all 23 display modes of the dev
 * machine, 800x600 through 3840x2160 (the {@code front-plate-sweep} fixtures). Hence:
 * <ul>
 *   <li>{@link #scale()} is {@code min(width/3840, height/2160)};</li>
 *   <li>{@link #x(double)} and {@link #y(double)} keep a point's scaled distance from the screen
 *       <b>centre</b> (on any 16:9 screen that reduces to plain proportional scaling);</li>
 *   <li>{@link #len(double)} scales linearly, {@link #area(double)} quadratically (blob sizes
 *       are pixel counts).</li>
 * </ul>
 *
 * <p>At {@link #REFERENCE} every mapping is exactly the identity - which is what lets the
 * 34-frame calibration gate vouch for this parameterization. The absolute margins of the
 * luminance and blob-size thresholds do shrink with the scale (they are calibrated at 4K and
 * mapped, not re-measured), so treat very small modes with respect: one transient false pin-pop
 * was observed live at 1280x1024, a single poll that the two-identical-frames settle rule absorbs.
 */
public record Viewport(int width, int height) {

    /** The resolution every vision constant was calibrated and live-verified at. */
    public static final Viewport REFERENCE = new Viewport(3840, 2160);

    public Viewport {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("not a screen size: " + width + "x" + height);
        }
    }

    /** Uniform scale from reference pixels: the side the 16:9 view is fitted by sets it. */
    public double scale() {
        return Math.min(width / (double) REFERENCE.width(),
                height / (double) REFERENCE.height());
    }

    /** A reference x-position mapped onto this screen, anchored to the screen centre. */
    public double x(double refX) {
        return width / 2.0 + (refX - REFERENCE.width() / 2.0) * scale();
    }

    /** A reference y-position mapped onto this screen, anchored to the screen centre. */
    public double y(double refY) {
        return height / 2.0 + (refY - REFERENCE.height() / 2.0) * scale();
    }

    /** A reference length, distance or offset mapped onto this screen. */
    public double len(double refLength) {
        return refLength * scale();
    }

    /** A reference blob area (pixel count) mapped onto this screen. */
    public double area(double refArea) {
        return refArea * scale() * scale();
    }

    /** True for the calibrated 4K viewport - the only one verified against labelled frames. */
    public boolean isReference() {
        return equals(REFERENCE);
    }
}
