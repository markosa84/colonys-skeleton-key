package io.github.markosa84.colonysskeletonkey.vision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reference-to-viewport mapping. The identity case is the load-bearing one: at
 * {@link Viewport#REFERENCE} every mapping must be <b>exactly</b> the input, because that is what
 * lets the 34-frame reader gate certify the parameterization without new fixtures.
 */
class ViewportTest {

    /**
     * The origin says where the game's view sits on the desktop, and <b>nothing else</b>: every
     * mapping stays view-local, so the reader is handed the same coordinates whether the game fills
     * the screen or sits in a corner of it. Only {@link GameScreen} ever adds the origin back.
     */
    @Test
    void theOriginMovesTheGrabAndNothingElse() {
        Viewport atOrigin = new Viewport(2560, 1440);
        Viewport moved = new Viewport(640, 360, 2560, 1440);

        assertEquals(atOrigin.scale(), moved.scale());
        assertEquals(atOrigin.x(3090.0), moved.x(3090.0), "view-local: the window moved, the lock did not");
        assertEquals(atOrigin.y(798.0), moved.y(798.0));
        assertEquals(atOrigin.len(26.0), moved.len(26.0));
        assertEquals(atOrigin.area(700), moved.area(700));

        assertEquals(new Viewport(0, 0, 2560, 1440), atOrigin, "a view filling the screen starts at 0,0");
    }

    /** Calibration is about the size. A 4K window is just as calibrated wherever it sits. */
    @Test
    void aFourKWindowIsTheCalibratedSizeWhereverItIs() {
        assertTrue(new Viewport(120, 80, 3840, 2160).isReference());
        assertFalse(new Viewport(2560, 1440).isReference());
    }

    /** It goes in bug reports, so it has to name both halves. */
    @Test
    void itPrintsAsAGeometry() {
        assertEquals("2560x1440+640+360", new Viewport(640, 360, 2560, 1440).toString());
    }

    @Test
    void theReferenceViewportIsExactlyTheIdentity() {
        Viewport v = Viewport.REFERENCE;
        assertEquals(1.0, v.scale());
        assertEquals(3090.0, v.x(3090.0));   // the fan centre
        assertEquals(798.0, v.y(798.0));
        assertEquals(58.9, v.len(58.9));     // the depth step, sign and fraction intact
        assertEquals(-36.75, v.len(-36.75));
        assertEquals(250.0, v.area(250));    // the pin-pop threshold
        assertTrue(v.isReference());
    }

    @Test
    void sixteenToNineScalesEverythingProportionally() {
        Viewport v = new Viewport(1920, 1080); // exactly half of 4K
        assertEquals(0.5, v.scale());
        assertEquals(1545.0, v.x(3090.0));   // same aspect: centre-anchored = plain scaling
        assertEquals(399.0, v.y(798.0));
        assertEquals(13.0, v.len(26.0));
        assertEquals(175.0, v.area(700));    // areas scale with the square
        assertFalse(v.isReference());
    }

    @Test
    void widerAspectKeepsPositionsAnchoredToTheScreenCentre() {
        Viewport v = new Viewport(5120, 2160); // ultrawide, same height as 4K
        assertEquals(1.0, v.scale(), "wider than 16:9: height sets the scale (Hor+)");
        assertEquals(2560.0, v.x(1920.0), "the reference centre maps to the new centre");
        assertEquals(2560.0 + 1170.0, v.x(3090.0), "same centre distance as at 4K");
        assertEquals(798.0, v.y(798.0));
        assertEquals(26.0, v.len(26.0), "sizes follow height, not width");
    }

    @Test
    void narrowerAspectFitsByWidthAndStaysCentred() {
        Viewport v = new Viewport(1920, 1440); // 4:3: the 16:9 view is width-limited
        assertEquals(0.5, v.scale(), "narrower than 16:9: width sets the scale (Vert+)");
        assertEquals(960.0 + 585.0, v.x(3090.0), "same centre distance, at width scale");
        assertEquals(720.0 - 141.0, v.y(798.0), "vertically centred, not top-anchored");
        assertEquals(29.45, v.len(58.9), 1e-12);
    }

    @Test
    void rejectsImpossibleScreenSizes() {
        assertThrows(IllegalArgumentException.class, () -> new Viewport(0, 1080));
        assertThrows(IllegalArgumentException.class, () -> new Viewport(1920, -1));
    }

    /**
     * Blob sizes are pixel counts, so they scale with the <b>square</b> of the linear scale. Getting
     * this wrong would not fail loudly - it would quietly widen or narrow every pin threshold at
     * every resolution that is not 4K.
     */
    @Test
    void blobAreasScaleQuadraticallyEvenAtAnAwkwardScale() {
        Viewport v = new Viewport(1280, 720); // exactly a third of 4K
        double third = 1.0 / 3.0;

        assertEquals(third, v.scale(), 1e-12);
        assertEquals(400.0 * third, v.len(400.0), 1e-9, "a distance scales linearly");
        assertEquals(400.0 * third * third, v.area(400.0), 1e-9, "an area scales quadratically");
    }

    /** Only 4K is the calibrated resolution; everything else is scaled from it, and says so. */
    @Test
    void onlyTheReferenceViewportCallsItselfTheReference() {
        assertTrue(Viewport.REFERENCE.isReference());
        assertTrue(new Viewport(3840, 2160).isReference(), "it is a value, not an identity");
        assertFalse(new Viewport(1920, 1080).isReference());
    }
}
