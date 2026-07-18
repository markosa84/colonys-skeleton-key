package io.github.markosa84.colonysskeletonkey.vision;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.provider.Arguments;

/**
 * The labelled corpus, as test arguments: <b>what</b> each frame shows, in one place, for every
 * reader that wants to be judged against it.
 *
 * <p>{@link TestFrames} is the one place a frame is <i>read</i>; this is the one place a frame's
 * <i>label</i> lives. The two readers used to carry a private copy of these providers each, which is
 * exactly the failure mode CLAUDE.md's "Where a fact belongs" is about: 241 labels stated twice, and
 * nothing to notice when a re-labelled frame is fixed in one copy and not the other.
 *
 * <p>Three groups, and they are labelled by three different arguments:
 * <ul>
 *   <li><b>The 4K census</b> - slide sequences where each frame differs from the last by exactly one
 *       plate step, so a reader wrong on any one frame breaks the whole sequence's arithmetic. Stated
 *       here, in {@link #censusFrames}, with the provenance of each sequence.</li>
 *   <li><b>The resolution sweep</b>, <b>the gamma slider</b> and <b>the HDR corpus</b> - each a replay
 *       of one key protocol, and each labelled by that protocol rather than by a reading: game state
 *       depends on the keys sent, not on the pixels that come back. Their labels are recorded beside
 *       the frames in a {@code labels.txt}, together with the protocol that establishes them, so they
 *       are read from there ({@link #labels}) rather than copied into Java. HDR was the newest such
 *       group and had only to bring its own {@code labels.txt}; a further one is the same.</li>
 * </ul>
 */
final class FrameCorpus {

    private FrameCorpus() {}

    /**
     * Every display mode of the dev machine. At each one, the same key protocol (R, A x2, then six D
     * presses) was replayed against the live game and a frame captured per settled state.
     */
    static final String[] SWEEP_MODES = {
            "3840x2160", "2560x1600", "2560x1440", "2048x1536", "1920x1440", "1920x1200",
            "1920x1080", "1680x1050", "1600x1200", "1600x1024", "1600x900", "1440x1080",
            "1366x768", "1360x768", "1280x1024", "1280x960", "1280x800", "1280x768",
            "1280x720",
    };

    /** The mode the sweep's labels were read at, and the group that records them. */
    private static final String SWEEP_SOURCE = "3840x2160/front-plate-sweep";

    /**
     * The 4K census: {@code (frame, offsets)}.
     *
     * <p>Where each sequence's labels come from:
     * <ul>
     *   <li><b>5p-plates-1-2-opposed/step-0</b> - the five offsets the user read off the screen and
     *       stated. <b>step-1..5</b> - the same lock as one plate is slid; plates 1 and 2 step by one
     *       in opposite directions per frame (an Inverted connection), every other plate frozen.</li>
     *   <li><b>6p-plate-5-sweep</b> - plate 5 stepped from one end of its track to the other: exactly
     *       the seven states -3..+3, nothing else moving.</li>
     *   <li><b>6p-plate-0-drags-1</b> - plate 0 slid from one end, dragging plate 1 the same way
     *       (Normal); plate 1 reaches -3 after three steps and blocks any further movement, which is
     *       why the user could only get half-way.</li>
     *   <li><b>6p-plates-0-and-5</b> - plate 0 slid end-to-end, then plate 5 slid end-to-end.</li>
     *   <li><b>6p-gap-shadow</b> - a difficulty-4 chest (live failure dump) whose inter-plate arch
     *       gap casts a hole-shaped shadow 2.25 spacings past the left end of plate 1's row. The user
     *       marked every real hole on a copy of this frame; the offsets follow from the hole lattice.
     *       Before the exact walk and the deskewed row gate the shadow read that row UNKNOWN (the
     *       double-skip window bridged onto it), so this frame pins both fixes.</li>
     *   <li><b>7p-*</b> - the first 7-plate lock ever captured, so its labels could not lean on a
     *       reader that was only ever verified at 4-6 plates. They were established the way the
     *       "Identifying which plate is selected" note prescribes: the lock's connections were probed
     *       live (a refused move leaves the lock untouched, so probing from one base configuration
     *       costs a strain and nothing else), and every state below was then <b>predicted from that
     *       model and the keys sent, before the frame was captured</b>. All 21 matched. Three
     *       independent signals back them up: the pin <b>pop</b> - a different code path, and a
     *       physically distinct game signal - lands on reported offset 0 in every frame, including
     *       ones with three and four simultaneous pops; moves were refused exactly where the model
     *       says a plate would run off its track; and a pixel diff of a swept plate's two extremes
     *       shows exactly one plate body moving, its pin standing still.</li>
     * </ul>
     */
    static Stream<Arguments> censusFrames() {
        List<Arguments> frames = new ArrayList<>();
        // 5 plates: plates 1 and 2 slide in opposite directions.
        frames.add(census("5p-plates-1-2-opposed/step-0.png", 0, 3, -2, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-1.png", 0, 2, -1, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-2.png", 0, 1, 0, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-3.png", 0, 0, 1, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-4.png", 0, -1, 2, 3, 3));
        frames.add(census("5p-plates-1-2-opposed/step-5.png", 0, -2, 3, 3, 3));
        // 6 plates: plate 5 through all seven states.
        for (int k = 0; k < 7; k++) {
            frames.add(census("6p-plate-5-sweep/step-" + k + ".png", 3, 0, 1, 2, -3, k - 3));
        }
        // 6 plates: plate 0 half-way, blocked by the plate it drags.
        for (int k = 0; k < 4; k++) {
            frames.add(census("6p-plate-0-drags-1/step-" + k + ".png", 3 - k, -k, 1, 2, -3, -2));
        }
        // 6 plates: plate 0 end-to-end, then plate 5 end-to-end.
        for (int k = 0; k < 6; k++) {
            frames.add(census("6p-plates-0-and-5/step-0" + k + ".png", 3 - k, -1, 0, 2 - k, 3, -2));
        }
        // step-05 -> step-06 is not another plate-0 step: plate 3 is already at -3, so that move
        // would be invalid. Plate 2 was moved instead, dragging plate 0 with it.
        frames.add(census("6p-plates-0-and-5/step-06.png", -3, -1, -1, -3, 3, -2));
        for (int k = 0; k < 7; k++) {
            frames.add(census("6p-plates-0-and-5/step-" + String.format("%02d", 7 + k) + ".png",
                    -3, -1, -1, -3, 3, k - 3));
        }
        // 6 plates, difficulty-4 chest: an arch-gap shadow lattice-aligned past the left end of
        // plate 1's row once read that row UNKNOWN. Pins the exact walk + the deskewed row gate.
        frames.add(census("6p-gap-shadow/step-0.png", 2, 1, 1, -2, 3, -3));

        // 7 plates (a difficulty-4 chest). Until these frames the 7-plate fan geometry and the
        // rotation angle were pure extrapolation - no 7-plate screenshot existed. START is
        // {0,-2,-3,3,3,2,3}; the connections, probed live, are 0->4(N), 3->1(N),4(N), 6->1(N),
        // and plates 1, 2 and 5 drag nothing.
        //
        // Plate 2 drags nothing and starts at -3, so it sweeps its whole track alone.
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-2-sweep/step-" + k + ".png", chest(k)));
        }
        // The FRONT row, end to end: plate 6 drags plate 1 (Normal), and plate 1 was first raised
        // clear to +3 so the pair can descend together without either leaving its track.
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-6-drags-1/step-" + k + ".png",
                    0, 3 - k, -3, 3, 3, 2, 3 - k));
        }
        // The BACK row, end to end: plate 0 drags plate 4 (Normal). Plate 3 (which drags 4 but not
        // 0) was used first to bring plate 4 down to 0, aligning the pair so they can sweep +3..-3
        // together. Front and back are the rows where one global rotation angle fits worst, so
        // these two sequences are what the extrapolated geometry most needed.
        for (int k = 0; k <= 6; k++) {
            frames.add(census("7p-plate-0-drags-4/step-" + k + ".png",
                    3 - k, 0, -3, 0, 3 - k, 2, 3));
        }
        return frames.stream();
    }

    /**
     * The whole gamma slider: {@code (frame, viewport, offsets)}. The 7-plate chest, plate 2 swept
     * end to end - it drags nothing, so it moves alone and the labels are the protocol's, not a
     * reading's. The same sequence was replayed at each setting; see {@code gamma/labels.txt}.
     */
    static Stream<Arguments> gammaFrames() {
        Map<String, int[]> labels = labels("gamma");
        List<Arguments> frames = new ArrayList<>();
        labels.forEach((name, offsets) ->
                frames.add(Arguments.of("gamma/" + name, Viewport.REFERENCE, offsets)));
        // The dark end at the reporter's own resolution: gamma and scale are independent faults, and
        // this is the one configuration that was actually reported broken. It is the same protocol
        // replayed at 2560x1440, so it is the g-1.2 sweep's labels - which is what the group's
        // labels.txt says of it, and why it carries none of its own.
        for (int k = 0; k <= 6; k++) {
            frames.add(Arguments.of("2560x1440/gamma-1.2-sweep/step-" + k + ".png",
                    new Viewport(2560, 1440), label(labels, "g-1.2/step-" + k + ".png")));
        }
        return frames.stream();
    }

    /**
     * The HDR corpus: {@code (frame, viewport, offsets)}. The same 7-plate chest and key protocol as
     * the gamma slider, captured with the game's HDR mode on - the first labelled HDR frames. HDR is
     * <b>not</b> the gamma slider: it is not an invertible per-pixel LUT (the SDR capture clips where
     * HDR does not, and the lost highlights cannot be recovered), so it cannot join {@link Tone}'s
     * family and is read tone-free instead. See {@code hdr/labels.txt} for the protocol and the
     * measurement, {@link LatticeReaderTest} for the reads, and {@code HdrCorpusTest} for the
     * off-family panel and the legacy reader's refusal.
     */
    static Stream<Arguments> hdrFrames() {
        Map<String, int[]> labels = labels("hdr");
        List<Arguments> frames = new ArrayList<>();
        labels.forEach((name, offsets) ->
                frames.add(Arguments.of("hdr/" + name, Viewport.REFERENCE, offsets)));
        return frames.stream();
    }

    /**
     * The front-plate sweep at every one of the 19 display modes: {@code (frame, viewport, offsets)}.
     * One 5-plate lock, one key protocol, replayed from a fresh R at each mode - so the states read
     * once at 4K are every mode's states too, which is the claim {@code labels.txt} records and this
     * is the code that acts on it.
     */
    static Stream<Arguments> sweepFrames() {
        Map<String, int[]> labels = labels(SWEEP_SOURCE);
        List<Arguments> frames = new ArrayList<>();
        for (String mode : SWEEP_MODES) {
            String[] wh = mode.split("x");
            Viewport viewport = new Viewport(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            labels.forEach((name, offsets) -> frames.add(
                    Arguments.of(mode + "/front-plate-sweep/" + name, viewport, offsets)));
        }
        return frames.stream();
    }

    /** Every labelled frame with its viewport - what the whole-corpus contract is judged on. */
    static Stream<Arguments> everyLabelledFrame() {
        List<Arguments> frames = new ArrayList<>();
        censusFrames().forEach(a ->
                frames.add(Arguments.of(a.get()[0], Viewport.REFERENCE, a.get()[1])));
        gammaFrames().forEach(frames::add);
        hdrFrames().forEach(frames::add);
        sweepFrames().forEach(frames::add);
        return frames.stream();
    }

    /** The 7-plate chest with plate 2 at {@code k - 3}, every other plate at START. */
    static int[] chest(int k) {
        return new int[] {0, -2, k - 3, 3, 3, 2, 3};
    }

    /**
     * A group's {@code labels.txt}: {@code name = [a, b, c]} lines, {@code #} comments, in file
     * order. The file is the label's home - it records the protocol the labels come from beside the
     * frames they describe - so a group that has one is never labelled twice.
     */
    static Map<String, int[]> labels(String group) {
        Map<String, int[]> out = new LinkedHashMap<>();
        List<String> lines;
        try {
            lines = Files.readAllLines(TestFrames.path(group + "/labels.txt"));
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + group + "/labels.txt", e);
        }
        for (String line : lines) {
            String text = line.trim();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            int eq = text.indexOf('=');
            if (eq < 0) {
                throw new IllegalStateException(group + "/labels.txt: not a label: " + text);
            }
            out.put(text.substring(0, eq).trim(), offsets(group, text.substring(eq + 1).trim()));
        }
        if (out.isEmpty()) {
            throw new IllegalStateException(group + "/labels.txt holds no labels");
        }
        return out;
    }

    /** {@code [0, -2, -3]} as the offsets it spells. */
    private static int[] offsets(String group, String csv) {
        if (!csv.startsWith("[") || !csv.endsWith("]")) {
            throw new IllegalStateException(group + "/labels.txt: not a state: " + csv);
        }
        String[] fields = csv.substring(1, csv.length() - 1).split(",");
        int[] out = new int[fields.length];
        for (int i = 0; i < fields.length; i++) {
            out[i] = Integer.parseInt(fields[i].trim());
        }
        return out;
    }

    /** A frame's labels, or a loud failure - a silently absent label would weaken the gate. */
    private static int[] label(Map<String, int[]> labels, String name) {
        int[] offsets = labels.get(name);
        if (offsets == null) {
            throw new IllegalStateException("No label for " + name);
        }
        return offsets;
    }

    private static Arguments census(String path, int... expected) {
        return Arguments.of(path, expected);
    }
}
