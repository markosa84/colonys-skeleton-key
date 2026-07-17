package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The affine map every reader actually uses, and the claim that extracting it changed nothing.
 *
 * <p>{@link Viewport} did this arithmetic inline for a year, phrased as an aspect-fit about the
 * view's centre. {@link ViewMapping} is the same function written the other way round - a scale and
 * an offset - which is the only form a mapping solved from the lock's own pixels can take. The
 * equivalence is what lets the whole 241-frame corpus go on vouching for the readers unchanged, so
 * it is asserted rather than asserted-in-a-comment: at every mode the corpus actually covers, and
 * exactly at the reference, where the identity is load-bearing.
 */
class ViewMappingTest {

    /**
     * <b>The load-bearing case.</b> At 4K the mapping must be the exact identity, not merely close:
     * that is what makes the calibration gate a gate on the parameterization too, rather than on one
     * lucky rounding.
     */
    @Test
    void theReferenceMappingIsExactlyTheIdentity() {
        ViewMapping m = Viewport.REFERENCE.mapping();

        assertEquals(ViewMapping.IDENTITY, m);
        assertEquals(3090.0, m.x(3090.0));   // the fan centre
        assertEquals(798.0, m.y(798.0));
        assertEquals(-36.75, m.len(-36.75)); // the depth step, sign and fraction intact
        assertEquals(250.0, m.area(250));    // the pin-pop threshold
    }

    /**
     * At every display mode the corpus covers, the mapping agrees with the viewport it came from -
     * on positions, lengths and areas alike. Both forms are exact algebra; only the floating-point
     * association differs, so the agreement is to the last few bits rather than approximate.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMode")
    void theMappingIsWhatTheViewportAlreadyComputed(Viewport vp) {
        ViewMapping m = vp.mapping();

        assertEquals(vp.scale(), m.scale(), 1e-9, vp + ": scale");
        for (double ref : new double[] {0, 798, 1080, 1920, 2450, 3090, 3520, 3840}) {
            assertEquals(vp.x(ref), m.x(ref), 1e-9, vp + ": x(" + ref + ")");
            assertEquals(vp.y(ref), m.y(ref), 1e-9, vp + ": y(" + ref + ")");
            assertEquals(vp.len(ref), m.len(ref), 1e-9, vp + ": len(" + ref + ")");
            assertEquals(vp.area(ref), m.area(ref), 1e-9, vp + ": area(" + ref + ")");
        }
    }

    /** The origin is capture metadata: two views of a size map the lock to the same place. */
    @Test
    void aMappingCarriesNoOriginBecauseTheReaderNeedsNone() {
        assertEquals(new Viewport(2560, 1440).mapping(),
                new Viewport(640, 360, 2560, 1440).mapping());
    }

    @Test
    void rejectsAMappingThatCouldNotDescribeAView() {
        assertThrows(IllegalArgumentException.class, () -> new ViewMapping(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ViewMapping(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ViewMapping(1, Double.NaN, 0));
    }

    // -- and the consumers agree, which is what the refactor has to prove --------------------------

    /**
     * A reader built from a mapping reads the same lock as one built from the viewport that mapping
     * came from. Trivially true of the arithmetic, and worth a frame anyway: this is the substitution
     * the recovery path makes, and it makes it on a real capture, not on a scale.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMode")
    void aReaderOnTheMappingReadsWhatAReaderOnTheViewportReads(Viewport vp) {
        BufferedImage img = TestFrames.load(vp.width() + "x" + vp.height()
                + "/front-plate-sweep/step-0.png");

        LatticeReader viaViewport = new LatticeReader(vp);
        LatticeReader viaMapping = new LatticeReader(vp.mapping(), Tone.CALIBRATED);

        int n = viaViewport.detectPlateCount(img);
        assertEquals(5, n, vp + ": the sweep frames are a 5-plate lock");
        assertEquals(n, viaMapping.detectPlateCount(img), vp + ": plate count");
        assertArrayEquals(viaViewport.readState(img, n), viaMapping.readState(img, n),
                vp + ": offsets");
    }

    /** The legacy reader too - both take the mapping, and the recovery path may be pointed at either. */
    @Test
    void theLegacyReaderTakesAMappingAsWell() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");

        LockReader reader = new LockReader(Viewport.REFERENCE.mapping(), Tone.CALIBRATED);

        assertEquals(6, reader.detectPlateCount(img));
        assertArrayEquals(new int[] {2, 1, 1, -2, 3, -3}, reader.readState(img, 6));
    }

    /** And the boxes, which is where a disagreement would cost a stale grab rather than a bad read. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("everyMode")
    void theCaptureBoxesComeOutTheSameThroughEitherForm(Viewport vp) {
        assertEquals(GameScreen.lockBox(vp), GameScreen.lockBox(vp.mapping()), vp + ": lock box");
        assertEquals(GameScreen.picksBox(vp), GameScreen.picksBox(vp.mapping()), vp + ": picks box");
    }

    /**
     * The gamma probe follows the mapping, not the viewport. It is a box at a fixed <b>reference</b>
     * position, so a mapping that moves the lock moves the panel with it - and estimating through
     * the viewport's own mapping instead would probe whatever happens to sit where the panel used to
     * be, and hand the reader a curve measured off the room.
     */
    @Test
    void theToneProbeFollowsTheMappingItIsGiven() {
        BufferedImage img = TestFrames.load("6p-gap-shadow/step-0.png");

        Tone atTheReference = Tone.estimate(img, Viewport.REFERENCE.mapping());
        Tone somewhereElse = Tone.estimate(img, new ViewMapping(1, -600, -400));

        assertEquals(true, atTheReference.isCalibrated(), atTheReference.describe());
        assertEquals(true, somewhereElse.isGuess(),
                "the panel is not there, so there is no gamma to read: " + somewhereElse.describe());
    }

    /**
     * A grab box built from a moved mapping moves with it. This is the whole point of the extraction:
     * the viewport still says where the <b>view</b> is, and the mapping says where the <b>lock</b> is
     * within it, and the two are allowed to disagree.
     */
    @Test
    void theBoxesFollowTheMappingWhileTheOriginStaysTheViewports() {
        Rectangle atOrigin = GameScreen.lockBox(Viewport.REFERENCE.mapping());
        Rectangle shifted = GameScreen.lockBox(new ViewMapping(1, 120, 80));

        assertEquals(atOrigin.width, shifted.width, "same scale, same size");
        assertEquals(atOrigin.height, shifted.height);
        assertEquals(atOrigin.x + 120, shifted.x);
        assertEquals(atOrigin.y + 80, shifted.y);
    }

    static Stream<Viewport> everyMode() {
        List<Viewport> all = new ArrayList<>();
        for (String mode : FrameCorpus.SWEEP_MODES) {
            String[] wh = mode.split("x");
            all.add(new Viewport(Integer.parseInt(wh[0]), Integer.parseInt(wh[1])));
        }
        return all.stream();
    }
}
