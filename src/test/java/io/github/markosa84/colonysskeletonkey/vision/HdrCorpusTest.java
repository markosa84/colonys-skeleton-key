package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HDR corpus, and what it pins that the per-frame reads in {@link LatticeReaderTest} do not: that
 * these frames are <b>genuinely HDR</b> (not merely a dark gamma), and that the tone-trusting legacy
 * reader <b>refuses</b> rather than reading a wrong lock.
 *
 * <p>HDR is the failure mode three players have reported: the tool reading nothing over a screenshot
 * that looks perfectly normal. Unlike the gamma slider it is <b>not</b> an invertible per-pixel LUT -
 * the SDR capture clips where HDR does not, and the lost highlights cannot be recovered (measured in
 * {@code hdr/labels.txt}: SDR 255 fans out across HDR 153..234). So it cannot join {@link Tone}'s
 * family, and it does not need to: {@link LatticeReader} reads from the lock's own contrast, which
 * never wanted absolute levels. This class guards the two ends of that story; the reads themselves,
 * and the whole-corpus safety contract, are {@link LatticeReaderTest} and {@link AnalyzerContractTest}.
 *
 * <p>No game and no display: pure frame analysis, read headless from the shrunk PNGs.
 */
class HdrCorpusTest {

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is missing"
                        + " from this checkout - the HDR corpus lives there");
    }

    /**
     * These are HDR frames, not merely dark ones. An HDR tonemap moves the panel's white far off where
     * its ink says it should sit on the gamma family (here: ink 37, white 199, against a family that
     * expects ~255), so {@link Tone#isOffFamily()} fires. This is what makes the whole corpus
     * meaningful - if it ever stopped holding, these frames would have become something other than the
     * HDR captures they are, and every other HDR assertion would be testing the wrong thing.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hdrFrames")
    void everyFrameIsGenuinelyOffFamily(String frame, Viewport viewport, int[] expected) {
        Tone tone = Tone.estimate(TestFrames.load(frame), viewport);

        assertTrue(tone.isOffFamily(),
                frame + ": panel ink " + tone.ink() + ", white " + tone.white() + " is on the gamma"
                        + " family - is this really an HDR frame? The corpus depends on it being off.");
    }

    /**
     * The legacy reader trusts the {@link Tone}, and on an off-family HDR frame the nearest measured
     * curve is worse than nothing - it crushes the brass below {@code isPin}'s threshold - so it finds
     * no fan and returns -1. That is the <b>safe</b> answer (a refusal, never a wrong lock), and it is
     * the specific fact behind the {@link AnalyzerContractTest} guarantee: here the legacy reader does
     * not merely avoid a <i>wrong</i> count, it declines to read at all rather than guess. If a future
     * change ever taught it to read HDR, this is the test that should be revisited deliberately.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("hdrFrames")
    void theLegacyReaderRefusesRatherThanGuessing(String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LockReader legacy = new LockReader(viewport, Tone.estimate(img, viewport));

        assertEquals(-1, legacy.detectPlateCount(img),
                frame + ": the legacy reader must refuse an HDR frame, not read it - a wrong read here"
                        + " is exactly the bug the tone-free default exists to avoid");
    }

    static Stream<Arguments> hdrFrames() {
        return FrameCorpus.hdrFrames();
    }
}
