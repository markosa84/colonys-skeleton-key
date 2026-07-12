package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity of the viewport parameterization on real frames: downscale a labelled 4K fixture and
 * read it with a reader configured for that smaller viewport.
 *
 * <p>A bilinear downscale is <b>not</b> a real render at that resolution - the game's own
 * antialiasing differs - so passing here checks that the geometry mapping is coherent (positions,
 * spacings, blob sizes and thresholds all transform together), not that the resolution is
 * validated. Real validation still needs frames captured in-game; see CLAUDE.md.
 */
class LockReaderScalingTest {

    /** The frames ship with the repository: a checkout without them is broken, so fail, never skip. */
    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is"
                        + " missing from this checkout");
    }

    @Test
    void readsAHalfSizeFrameWithAHalfSizeReader() {
        Viewport viewport = new Viewport(1920, 1080);
        BufferedImage frame = downscale(TestFrames.load("5p-plates-1-2-opposed/step-0.png"), viewport);
        LockReader reader = new LockReader(viewport);
        assertEquals(5, reader.detectPlateCount(frame));
        assertArrayEquals(new int[] {0, 3, -2, 3, 3}, reader.readState(frame, 5));
    }

    @Test
    void readsATwoThirdsFrameWithATwoThirdsReader() {
        Viewport viewport = new Viewport(2560, 1440); // a non-integer scale exercises the rounding
        BufferedImage frame = downscale(TestFrames.load("6p-plate-5-sweep/step-3.png"), viewport);
        LockReader reader = new LockReader(viewport);
        assertEquals(6, reader.detectPlateCount(frame));
        assertArrayEquals(new int[] {3, 0, 1, 2, -3, 0}, reader.readState(frame, 6));
    }

    private static BufferedImage downscale(BufferedImage src, Viewport to) {
        BufferedImage out = new BufferedImage(to.width(), to.height(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, to.width(), to.height(), null);
        g.dispose();
        return out;
    }
}
