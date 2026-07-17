package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the capture geometry can never crop the lock: at every supported viewport and for every
 * plate count, the screen contains {@link GameScreen}'s lock capture box, and that box contains
 * everything {@link LockReader} reads - the pin scan box and the full rotated fan crop - with a
 * safety belt to spare.
 *
 * <p>The fan-crop bound depends only on the plate count, never on the offsets: it is sized from
 * the row gates, which already span the whole track (see
 * {@link LockReader#fanCropScreenBounds}). So containment proven here holds for <b>any lock in
 * any state</b>, which is the invariant that makes a misread a reader bug by definition, never a
 * cropping one.
 */
class CaptureBoxTest {

    /** Safety belt (4K px) the lock box must keep around everything the reader samples. */
    private static final int BELT = 16;
    /** Margin (4K px) the pin scan box must keep around every expected pin centre. */
    private static final int PIN_MARGIN = 24;
    /** A popped (centred) pin's centroid sits ~10px above its fan position - measured, 4K. */
    private static final int POPPED_PIN_RISE = 10;

    static Stream<Viewport> viewports() {
        List<Viewport> all = new ArrayList<>();
        all.add(Viewport.REFERENCE);
        for (String mode : FrameCorpus.SWEEP_MODES) {
            String[] wh = mode.split("x");
            all.add(new Viewport(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
        }
        return all.stream().distinct();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewports")
    void theLockBoxStaysOnScreen(Viewport vp) {
        Rectangle screen = new Rectangle(0, 0, vp.width(), vp.height());
        assertContains(screen, GameScreen.lockBox(vp), 0, vp + ": lock box on screen");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewports")
    void theLockBoxContainsEveryFanCropWithABelt(Viewport vp) {
        Rectangle lockBox = GameScreen.lockBox(vp);
        LockReader reader = new LockReader(vp);
        int belt = scaled(BELT, vp);
        for (int n = LockModel.MIN_PLATES; n <= LockModel.MAX_PLATES; n++) {
            assertContains(lockBox, reader.fanCropScreenBounds(n), belt,
                    vp + ": " + n + "-plate fan crop inside the lock box");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewports")
    void theLockBoxContainsThePinScanBoxWithABelt(Viewport vp) {
        assertContains(GameScreen.lockBox(vp), new LockReader(vp).pinBox(), scaled(BELT, vp),
                vp + ": pin scan box inside the lock box");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewports")
    void thePinScanBoxContainsEveryPinFlatOrPopped(Viewport vp) {
        LockReader reader = new LockReader(vp);
        Rectangle pinBox = reader.pinBox();
        int margin = scaled(PIN_MARGIN, vp);
        double rise = vp.len(POPPED_PIN_RISE);
        for (int n = LockModel.MIN_PLATES; n <= LockModel.MAX_PLATES; n++) {
            for (int i = 0; i < n; i++) {
                double[] pin = reader.pinPosition(n, i);
                assertPoint(pinBox, pin[0], pin[1], margin,
                        vp + ": flat pin " + i + " of " + n);
                assertPoint(pinBox, pin[0], pin[1] - rise, margin,
                        vp + ": popped pin " + i + " of " + n);
            }
        }
    }

    /** A reference margin mapped to the viewport, floored so it never rounds away entirely. */
    private static int scaled(int referencePx, Viewport vp) {
        return Math.max(4, (int) Math.floor(vp.len(referencePx)));
    }

    private static void assertContains(Rectangle outer, Rectangle inner, int margin, String what) {
        assertTrue(inner.x >= outer.x + margin
                        && inner.y >= outer.y + margin
                        && inner.x + inner.width <= outer.x + outer.width - margin
                        && inner.y + inner.height <= outer.y + outer.height - margin,
                what + ": " + inner + " not inside " + outer + " with margin " + margin);
    }

    private static void assertPoint(Rectangle box, double x, double y, int margin, String what) {
        assertTrue(x >= box.x + margin && x <= box.x + box.width - margin
                        && y >= box.y + margin && y <= box.y + box.height - margin,
                what + ": (" + x + ", " + y + ") not inside " + box + " with margin " + margin);
    }
}
