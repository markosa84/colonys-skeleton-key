package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link LockAnalyzer} promises its callers, asked of <b>every</b> implementation on <b>every</b>
 * labelled frame. The exact reads are each reader's own business - {@link LockReaderTest} and
 * {@link LatticeReaderTest} pin those, and the two readers do not have to be equally good. These
 * three are different: they are the properties {@code LockSession} leans on, and a reader that breaks
 * one of them does not merely read badly, it costs the player lockpicks.
 *
 * <ul>
 *   <li><b>Never a wrong plate count.</b> The right count or -1 are both safe; a plausible-but-wrong
 *       4..7 is not - it hands the session a model with the wrong number of plates to drive into
 *       walls. This is the bug a reporter actually paid for: a 6-plate chest read as 4, nine strains,
 *       "Stuck".</li>
 *   <li><b>Never a false pop.</b> {@code readCentered} is the only signal a lock may be declared open
 *       from, so the error it must not make is saying a plate is centred when it is not. A missed pop
 *       - a real one read too faint - only costs a re-read, and does happen at small resolutions.</li>
 *   <li><b>Every offset is a real one</b> - in {@code [-3, +3]}, or {@link LockModel#UNKNOWN}, which
 *       must mean "refused" and never "guessed": the session's whole occlusion machinery leans on
 *       that.</li>
 * </ul>
 *
 * <p>Both readers are held to all three even where one of them is expected to answer -1 (the legacy
 * reader on an HDR frame, say). Refusing is not a failure of this contract; it is one of the two
 * safe answers, and the point of stating the contract separately from the reads.
 */
class AnalyzerContractTest {

    @BeforeAll
    static void framesArePresent() {
        assertTrue(TestFrames.available(),
                "the labelled frames are part of this repository, but src/test/data/frames is"
                        + " missing from this checkout - every reader's validation lives there");
    }

    /** One implementation of the seam, named as {@code --reader} names it. */
    private record Reader(String name, BiFunction<Viewport, Tone, LockAnalyzer> build) {
        LockAnalyzer of(BufferedImage frame, Viewport viewport) {
            return build.apply(viewport, Tone.estimate(frame, viewport));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final List<Reader> READERS = List.of(
            new Reader("lattice", LatticeReader::new),
            new Reader("legacy", LockReader::new));

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("everyFrameThroughEveryReader")
    void neverAnswersAWrongPlateCount(Reader reader, String frame, Viewport viewport,
            int[] expected) {
        BufferedImage img = TestFrames.load(frame);

        int n = reader.of(img, viewport).detectPlateCount(img);

        assertTrue(n == expected.length || n == -1,
                frame + ": plate count was " + n + ", which is neither the truth (" + expected.length
                        + ") nor a refusal (-1)");
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("everyFrameThroughEveryReader")
    void neverFalsePops(Reader reader, String frame, Viewport viewport, int[] expected) {
        BufferedImage img = TestFrames.load(frame);

        boolean[] centred = reader.of(img, viewport).readCentered(img, expected.length);

        for (int i = 0; i < expected.length; i++) {
            if (centred[i]) {
                assertEquals(0, expected[i],
                        frame + ": plate " + i + " read as popped, but its offset is " + expected[i]);
            }
        }
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("everyFrameThroughEveryReader")
    void everyOffsetIsInRangeOrUnknown(Reader reader, String frame, Viewport viewport,
            int[] expected) {
        BufferedImage img = TestFrames.load(frame);
        LockAnalyzer analyzer = reader.of(img, viewport);

        int n = analyzer.detectPlateCount(img);
        if (n < LockModel.MIN_PLATES) {
            return; // a refusal reads nothing, and has nothing to promise about what it read
        }
        for (int v : analyzer.readState(img, n)) {
            assertTrue(v == LockModel.UNKNOWN
                            || (v >= -LockModel.MAX_OFFSET && v <= LockModel.MAX_OFFSET),
                    frame + ": offset " + v + " is out of range");
        }
    }

    static Stream<Arguments> everyFrameThroughEveryReader() {
        List<Arguments> cases = new ArrayList<>();
        for (Reader reader : READERS) {
            FrameCorpus.everyLabelledFrame().forEach(frame -> cases.add(
                    Arguments.of(reader, frame.get()[0], frame.get()[1], frame.get()[2])));
        }
        return cases.stream();
    }
}
