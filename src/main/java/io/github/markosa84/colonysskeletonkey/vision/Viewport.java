package io.github.markosa84.colonysskeletonkey.vision;

/**
 * The rectangle the game renders into, and the mapping from the calibrated 4K {@link #REFERENCE}
 * coordinates onto it. Every pixel constant in the vision layer is a value <b>measured at 4K</b>;
 * this record is the one place that turns those measurements into coordinates for the view the
 * game is actually drawing.
 *
 * <p><b>It is the game's window, not the screen.</b> The two coincide only when the game fills the
 * primary display - which the tool assumed for its first year, and which is false for anyone
 * running the game at less than their desktop resolution, in a window, or on a second monitor. A
 * viewport that describes the wrong rectangle does not fail loudly: {@link LockReader} clamps its
 * scan box to the frame and simply finds no pins, so the tool reports "no lock detected" over a
 * screenshot that looks perfectly fine to a human. Hence {@link #originX} / {@link #originY}: where
 * the game's view sits in <b>virtual screen</b> coordinates.
 *
 * <p>The origin is capture metadata and nothing else. {@link GameScreen} adds it when it asks the
 * screen grabber for pixels; every other mapping here stays <b>view-local</b>, so the images handed
 * to {@link LockReader} always have the game's top-left corner at (0, 0) and the reader never needs
 * to know the window moved.
 *
 * <p>The mapping is an <b>aspect-fit</b>, measured against the live game: the 16:9 reference view
 * is fitted inside the view and stays centred. Wider aspects show more world at the sides
 * (Hor+, height sets the scale); narrower aspects (16:10, 4:3, 5:4) show more above and below
 * (Vert+, width sets the scale) - verified by live lock sweeps at all 23 display modes of the dev
 * machine, 800x600 through 3840x2160 (the {@code front-plate-sweep} fixtures). Hence:
 * <ul>
 *   <li>{@link #scale()} is {@code min(width/3840, height/2160)};</li>
 *   <li>{@link #x(double)} and {@link #y(double)} keep a point's scaled distance from the view's
 *       <b>centre</b> (on any 16:9 view that reduces to plain proportional scaling);</li>
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
public record Viewport(int originX, int originY, int width, int height) {

    /** The resolution every vision constant was calibrated and live-verified at. */
    public static final Viewport REFERENCE = new Viewport(3840, 2160);

    /** A view filling a screen whose top-left is the origin of the virtual desktop. */
    public Viewport(int width, int height) {
        this(0, 0, width, height);
    }

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

    /** A reference x-position mapped into this view, anchored to its centre. View-local. */
    public double x(double refX) {
        return width / 2.0 + (refX - REFERENCE.width() / 2.0) * scale();
    }

    /** A reference y-position mapped into this view, anchored to its centre. View-local. */
    public double y(double refY) {
        return height / 2.0 + (refY - REFERENCE.height() / 2.0) * scale();
    }

    /** A reference length, distance or offset mapped onto this view. */
    public double len(double refLength) {
        return refLength * scale();
    }

    /** A reference blob area (pixel count) mapped onto this view. */
    public double area(double refArea) {
        return refArea * scale() * scale();
    }

    /**
     * True for the calibrated 4K size - the resolution the reader's constants were fitted at, and
     * where every mapping above is the identity. About the <i>size</i> only: a 4K game window is
     * just as calibrated wherever it sits on the desktop.
     */
    public boolean isReference() {
        return width == REFERENCE.width() && height == REFERENCE.height();
    }

    /** e.g. {@code 2560x1440+0+0}, the X11 geometry spelling. For banners and bug reports. */
    @Override
    public String toString() {
        return width + "x" + height + "+" + originX + "+" + originY;
    }
}
