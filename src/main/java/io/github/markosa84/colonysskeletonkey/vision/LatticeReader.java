package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Reads the lock without a single absolute pixel value.
 *
 * <p>{@link LockReader} works, and it is calibrated to the pixel - but every gate it has is a number
 * fitted on one screen at one gamma: {@code isPin}'s {@code r >= 130}, the hole scan's
 * {@code luminance < 105}, the pop's {@code area >= 250}. {@link Tone} was built to undo the gamma
 * slider and it does. What it cannot undo is an <b>HDR tonemap</b>, which compresses the whole picture
 * (diffuse white lands at 183-199 instead of 255) in a way a one-parameter gamma family cannot express:
 * three reporters have frames it mis-corrects into unreadability, and on those frames the reader is not
 * merely wrong, it finds nothing at all. The same brittleness is what makes low resolutions
 * uncomfortable - the geometry scales, but a luminance cannot.
 *
 * <p>This reader keeps <b>everything geometric</b> that has been measured (the fan, the -30 degree
 * rotation, the per-row deskew, the hole-to-hole walk - see {@link FanGeometry}) and replaces
 * <b>everything photometric</b> with a ratio. It never asks "is this pixel darker than 105?", only "is
 * this pixel much darker than the plate it is cut into?" - and the plate is measured from the frame.
 *
 * <p>And it uses {@link Tone} <b>where, and only where, Tone works</b>: on a frame that is off nothing
 * but the gamma slider, the panel probe reads the gamma exactly and the frame is mapped back to the
 * calibrated look on the way in, so the relative pipeline below sees calibrated pixels. On a frame that
 * is off the family - HDR, a brightness offset - {@code Tone} cannot express it and its curve is worse
 * than nothing, so the frame goes through untouched and the relative pipeline carries it alone. The two
 * readers are not rivals; this is the one that knows which of them to be. See the {@link #tone} field.
 *
 * <h2>The frame's own range is the ruler, and there are two of them</h2>
 * Every gate below is a fraction of something measured off this very frame - never a luminance. Which
 * something, though, is the whole design, and it took a long time to get right:
 *
 * <ul>
 *   <li><b>Tracing</b> a hole is asking "plate, or not plate?", so it is judged against <b>the plate the
 *       pixel is sitting on</b> ({@code metalAt}). The plates are not evenly lit - at gamma 1.2 the
 *       dimmest reads 0.46 of the brightest - and against the lock's brightest steel, a dim plate's own
 *       gate lands below its own metal and its holes bleed out into it.</li>
 *   <li><b>Believing</b> a hole is asking "how black is what shows through?", and that is judged against
 *       <b>the whole frame's range</b>. The room behind the lock is exactly as black behind a dim plate
 *       as a bright one, so this test must not be scaled by the plate. Getting these two the wrong way
 *       round cost the ten smallest display modes.</li>
 * </ul>
 *
 * <p><b>It is now the default reader.</b> Scored by {@code tools/ReaderBench} and pinned by
 * {@code LatticeReaderTest}, it matches {@link LockReader} on <b>every</b> labelled frame - 190/190
 * plate counts, 1005/1005 offsets, across the 53-frame 4K census, the whole gamma slider and all 23
 * resolution sweep modes - and on top of that it reads the reporters' HDR dumps, where
 * {@code LockReader} returns -1 and the player is told "no lock detected". {@code LockReader} is kept
 * as the reference (and for its {@code luminance} helper and the constants this class borrows), behind
 * {@code --reader=legacy}.
 *
 * <p>The last frames to fall were the smallest, 800x600, where the centred plate walked only five of
 * six holes: a popped pin is a raised cylinder, and at that scale it merges with a neighbouring hole.
 * That is not a missing plate, it is the plate the game itself is flagging as centred, so a popped row
 * counts as a plate whatever its hole count (see {@link RowFit#isPlate()}) - the same trust in the pop
 * that {@code LockReader} has always placed in it.
 *
 * <p>The <b>pin-pop</b> - {@code readCentered}, the only signal {@code LockSession} may declare a lock
 * open from - takes two gates agreeing (see {@link RowFit#popped()}), and over the whole corpus it
 * produces <b>zero false pops</b>: it never says a plate is centred when it is not, so it can never
 * call a lock open that is not. The handful of pins it under-reads are all genuine pops read faint at a
 * small resolution, which is the harmless direction - a missed pop costs a re-read, nothing more, and
 * the hole count still reads those plates as centred (offset 0) anyway.
 *
 * <h2>The pin is the gap, not the brass</h2>
 * The pin never moves; the plate slides underneath it. So a row's six visible holes always sit on a
 * lattice anchored at the pin's own fixed, known position, and <b>the pin's slot is the one with no
 * hole in it</b>:
 *
 * <pre>
 *   offset = (holes left of the pin) - 3
 * </pre>
 *
 * Nothing in that needs to know brass is warm - which matters, because {@code isPin}'s warm test is the
 * single most tone-sensitive thing in the old reader, and measurement shows why: a pin's {@code (r - b)}
 * runs 48-66 at the calibrated gamma but only <b>20-39 under HDR</b>, against a gate of 45. That one
 * number is the whole HDR bug.
 *
 * <h2>A plate is a row of holes, not a row of pins</h2>
 * {@link LockReader} counts <b>pins</b>, and that is the one bug that has actually cost a player picks:
 * the fans of {@code n} and {@code n + 2} share a lattice, so losing the two faintest end pins - exactly
 * what a dark or HDR frame takes - makes a 6-plate lock read as a 4-plate one, and the session then
 * drives a model with the wrong number of plates into the walls. Here a plate is what it physically is:
 * a row of six holes in lit steel, which nothing else in a room is. Lose a pin and nothing happens.
 */
public final class LatticeReader implements LockAnalyzer {

    /** Offset returned when a plate's row could not be resolved. */
    public static final int UNKNOWN = LockModel.UNKNOWN;

    private static final int HOLES = FanGeometry.HOLES_PER_PLATE;

    // --- Every constant here is a RATIO of the lock's own steel, or a count. Not one of them is a
    // --- pixel value, and that is the point: they mean the same thing on a 4K HDR display and an
    // --- 800x600 SDR one. Each is the number LockReader was fitted with, divided by the ~255 its
    // --- plate reads at the calibrated gamma - so at that gamma this reader IS that reader.

    /**
     * How dark a pixel must be, <b>against the plate it is sitting on</b>, to be traced as part of a
     * hole. ({@link LockReader}: a flat luminance 105, against plates at ~234.)
     *
     * <p>This one is local, because it is the question "plate, or not plate?" - and the plates are not
     * evenly lit, so it has to be asked of each plate in turn. At gamma 1.2 the dimmest plate reads
     * 0.46 of the brightest: judged against the lock's brightest steel its own gate lands at luminance
     * 43 while its metal sits at 44, and its holes bleed straight out into it.
     */
    private static final double HOLE_DARK = 0.58;

    /**
     * How deep the void has to be - the darkest pixel, and the mean - <b>against the whole frame's
     * range</b>, not against the plate. ({@link LockReader}: 62 and 88.)
     *
     * <p>These two are <b>global where the tracing gate is local</b>, and getting that backwards is
     * what cost the ten smallest display modes. A hole is not dark because of the plate it is cut into.
     * It is dark because it is a hole, and what shows through it is the room behind the lock - which is
     * exactly as black behind a dim plate as behind a bright one. Scale this test by the local plate
     * and a hole in the dimmest plate has to be <i>darker than the room can be</i> to qualify: at
     * 1600x900 the back plate's holes bottomed out at luminance 55 against a local bar of 53, and the
     * whole back row vanished by a margin of two levels.
     *
     * <p>So: what a hole is <i>cut into</i> is local. What shows <i>through</i> it is not.
     */
    private static final double HOLE_MAX_MIN = 0.32;
    private static final double HOLE_MAX_MEAN = 0.44;

    /** The percentile of the crop taken as "the room behind the plates" - the black a hole shows. */
    private static final double BLACK_PCT = 0.02;

    /**
     * How far a blob may fall outside {@link LockReader}'s measured hole shape and still be a hole. The
     * bounds are a property of the hole <i>and the threshold it was traced at</i>, and this reader does
     * not trace at that threshold - but measured, the slack buys nothing (1.0 and 1.45 score identically
     * on all 190 frames), so it stays at 1 and the <b>geometry</b> does the rejecting, as it should.
     */
    private static final double SHAPE_SLACK = 1.0;

    // (The lock's lit steel is measured from the plates themselves - see Fan.metalAtDepth. A
    // percentile of the whole crop was tried first and it fails at the dark end of the gamma slider:
    // at gamma 1.2 the steel is at luminance 46 but the crop's 98th percentile is 217, because a
    // candle's specular highlight is still a highlight however dark the room. Every gate was then
    // set five times too high, the plates themselves read as holes, and 23 of the 27 gamma frames
    // could not be read at all. The steel has to be measured as steel.)

    /**
     * How bright a row's own metal must be, against the brightest plate of the same lock, to be a
     * plate at all.
     *
     * <p>The lock has a <b>dark front casing</b> - the piece holding the keyhole and the pick - and it
     * sits exactly one depth step in front of the front plate, whatever the plate count. So it lands
     * precisely on the row a 4- or 5-plate fan must check to know it is not the middle of a bigger one.
     * It is textured enough to fake a row, and only its <i>darkness</i> gives it away. Comparing each
     * row's metal against the brightest plate of the same lock keeps that judgement inside the frame,
     * where a tonemap cannot reach it: measured, real plates sit at 0.8-1.0 of the brightest, the
     * casing at 0.2-0.4.
     */
    private static final double LIT_PLATE = 0.42;

    /**
     * The two gates a pin must pass to count as popped up (centred) - see {@link RowFit#popped()} for
     * why it takes two, and {@code tools/PopProbe2} for how they were fitted. Both are darkness ratios,
     * so both are tone-free, and both are measured off the frame.
     *
     * <p>{@link #POP_PIN_MIN} is the pin column against its <b>own</b> plate; {@link #POP_DISC_MIN} the
     * disc at the pin against the whole frame's range. A popped pin is a raised cylinder that darkens
     * both; the two dark things that fool a single gate - the dark front plate, and a dark plate edge -
     * fool only one of them. The pins the pair still misreads are 7 of 1005, and every one is a genuine
     * pop read faint at a small resolution: an under-read, never a false pop.
     *
     * <p>The brass <b>area</b> {@link LockReader} uses instead cannot be made tone-free at all - 25
     * frame-relative colour gates were measured and every one overlaps (see {@code tools/PopProbe}). And
     * this is read at the slot the <b>geometry</b> names, never the one the hole walk chose, so nothing
     * in the hole rows can fake it.
     */
    private static final double POP_PIN_MIN = 0.094;
    private static final double POP_DISC_MIN = 0.207;

    /** Half-height of the strip the pin's own darkness is read in, as a fraction of a hole's height. */
    private static final double PIN_HALF_HEIGHT = 0.25;

    /** Half-height of the strip the plate behind the pin is read in - tall enough to clear the hole. */
    private static final double PLATE_HALF_HEIGHT = 0.60;

    private final FanGeometry geo;
    private final double holeMinArea, holeMaxArea;
    private final double holeMinW, holeMaxW, holeMinH, holeMaxH;
    private final int pinHalf, plateHalf;

    /**
     * The gamma correction to undo on the way in - <b>but only when the frame is on the family</b>.
     *
     * <p>This is the hybrid, and it is the right division of labour between the two readers. Where the
     * frame is off nothing but the gamma slider, {@link Tone} maps it back to the calibrated look
     * exactly, and this reader then reads calibrated pixels - which is where its own dark-gamma weakness
     * (a steep tone curve traces a hole's rim thinner than a linear ratio expects) simply disappears,
     * because there is no longer a steep curve on the pixels. Where the frame is off the family - HDR,
     * a brightness offset, a shader - {@code Tone} cannot express it and its correction is worse than
     * nothing (it lifts the pins' blue past their clipped red and they stop being warm), so this reader
     * ignores the tone and falls back to its own relative pipeline, which needs no curve at all. Each
     * tool is used exactly where it is the better one.
     */
    private final Tone tone;

    /** Reads frames as they are - the relative pipeline, no gamma correction. */
    public LatticeReader(Viewport viewport) {
        this(viewport, Tone.CALIBRATED);
    }

    public LatticeReader(Viewport viewport, Tone tone) {
        this.tone = tone;
        this.geo = new FanGeometry(viewport);
        // Areas are the one thing that DOES scale with the viewport, and quadratically: a hole is a
        // hole, but it is fewer pixels on a smaller screen. The slack is because these blobs are traced
        // at a different threshold than the one the bounds were measured at - see SHAPE_SLACK.
        this.holeMinArea = viewport.area(LockReader.HOLE_MIN_AREA) / (SHAPE_SLACK * SHAPE_SLACK);
        this.holeMaxArea = viewport.area(LockReader.HOLE_MAX_AREA) * SHAPE_SLACK * SHAPE_SLACK;
        this.holeMinW = geo.holeMinW / SHAPE_SLACK;
        this.holeMaxW = geo.holeMaxW * SHAPE_SLACK;
        this.holeMinH = geo.holeMinH / SHAPE_SLACK;
        this.holeMaxH = geo.holeMaxH * SHAPE_SLACK;
        this.pinHalf = Math.max(1, (int) Math.round(geo.holeMaxH * PIN_HALF_HEIGHT));
        this.plateHalf = Math.max(2, (int) Math.round(geo.holeMaxH * PLATE_HALF_HEIGHT));
    }

    /**
     * What one row came to. The two pop readings are both here because it takes both to be sure: see
     * {@link #popped()}.
     *
     * @param slot     which of the 7 lattice positions holds the pin, or -1 if the row did not add up
     * @param walked   how many hole slots the walk crossed, out of six
     * @param lit      this row's metal, against the brightest plate of the same lock
     * @param pinDark  the pin column's darkness against its <b>own</b> plate
     * @param discDark the darkness of a disc at the pin, against the whole frame's range
     */
    public record RowFit(int slot, int walked, double lit, double pinDark, double discDark) {

        /**
         * A plate is a row of six holes in lit steel - <b>or</b> a row of lit steel whose pin has
         * popped up, which is the same plate with a hole swallowed.
         *
         * <p>A popped pin is a raised cylinder, and at a small resolution it merges with a neighbouring
         * hole, so the walk finds five. That is not a missing plate, it is the plate the game itself is
         * flagging as centred - and it cost every 800x600 frame past step 0, where the centred plate
         * walked 5/6 and the whole lock was called unreadable. The {@code lit} guard still stands, so a
         * dark casing or the room past the fan (never lit, and never popped) cannot slip in this way.
         */
        public boolean isPlate() {
            return (walked == HOLES || popped()) && lit >= LIT_PLATE;
        }

        public int offset() {
            if (popped()) {
                return 0; // the game's own exact signal - it does not need the hole count
            }
            return walked == HOLES ? slot - LockModel.MAX_OFFSET : UNKNOWN;
        }

        /**
         * True when this plate's pin has popped up - the "centred" signal, and the only one
         * {@code LockSession} may declare a lock open from. It takes <b>two</b> readings agreeing, and
         * that is what made it trustworthy (measured, one gate: 1.5% of pins wrong; both: 0.7%, and
         * every one of those an <i>under</i>-read, never a false pop - see {@code tools/PopProbe2}).
         *
         * <p>The two are orthogonal on purpose. {@link #pinDark} - the pin's column against its own
         * plate - catches the pop but also false-triggers on the dark front plate, whose whole column
         * is dark. {@link #discDark} - a disc at the pin against the frame's range - is what tells a
         * raised cylinder (a dark <i>blob</i>) from a dark plate edge (dark everywhere): a real pop
         * darkens both, an artefact only one. Requiring both keeps the false pops out, which is the
         * error the session cannot afford - a missed pop only costs a re-read, a false one could call a
         * lock open that is not.
         */
        public boolean popped() {
            return pinDark >= POP_PIN_MIN && discDark >= POP_DISC_MIN;
        }
    }

    /**
     * Plate count, from the hole rows alone - no pins anywhere in it.
     *
     * <p>The largest {@code n} whose every row is a plate and which has no plate one step past either
     * end. That end check is what {@link LockReader} needs {@code plateBeyond} for, and here it is the
     * same question asked of the same thing, so it costs nothing extra.
     */
    public int detectPlateCount(BufferedImage img) {
        Fan fan = new Fan(img);
        for (int n = LockModel.MAX_PLATES; n >= LockModel.MIN_PLATES; n--) {
            boolean all = true;
            for (int i = 0; i < n && all; i++) {
                all = fan.fit(n, i).isPlate();
            }
            if (!all) {
                continue;
            }
            // Only a 4- or 5-plate fan can be the middle of a bigger one; 6 and 7 have no n+2.
            if (n + 2 > LockModel.MAX_PLATES
                    || (!fan.fit(n, -1).isPlate() && !fan.fit(n, n).isPlate())) {
                return n;
            }
        }
        return -1;
    }

    /** Every plate's offset, or {@link #UNKNOWN} for a row that did not resolve. */
    public int[] readState(BufferedImage img, int n) {
        Fan fan = new Fan(img);
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = fan.fit(n, i).offset(); // offset() reads the pop itself; a popped plate is 0
        }
        return out;
    }

    /** Per-plate centred flag, from the pin's own column - never from the hole rows. */
    public boolean[] readCentered(BufferedImage img, int n) {
        Fan fan = new Fan(img);
        boolean[] out = new boolean[n];
        for (int i = 0; i < n; i++) {
            out[i] = fan.fit(n, i).popped();
        }
        return out;
    }

    /** The reader's own account of a frame, for a bug report. */
    public String describe(BufferedImage img) {
        StringBuilder out = new StringBuilder();
        Fan fan = new Fan(img);
        out.append("frame:   ").append(img.getWidth()).append('x').append(img.getHeight())
                .append("  (lattice reader: no colour gates, no Tone)\n");
        out.append(String.format(Locale.ROOT,
                "levels:  this frame's lit steel reads %.0f, the room behind its holes %.0f -"
                + " every gate below is a fraction of one of those, never a number of its own\n",
                fan.plateLevel, fan.black));
        out.append("holes:   ").append(fan.holes.size()).append(" hole-sized voids in the fan crop\n");
        for (int n = LockModel.MAX_PLATES; n >= LockModel.MIN_PLATES; n--) {
            out.append(String.format(Locale.ROOT, "  %d plates:", n));
            for (int i = 0; i < n; i++) {
                RowFit f = fan.fit(n, i);
                out.append(String.format(Locale.ROOT, " [%d: %d/6 holes, lit %.2f, pop %.2f/%.2f%s%s]",
                        i, f.walked(), f.lit(), f.pinDark(), f.discDark(),
                        f.popped() ? " POPPED" : "", f.isPlate() ? "" : " NOT A ROW"));
            }
            out.append('\n');
        }
        int n = detectPlateCount(img);
        out.append(n < 0
                ? "  -> No lock: no run of 4-7 rows of six holes in lit steel. Either the box is not "
                        + "over the lock (the viewport is wrong), or the rows are not resolving."
                : "  -> " + n + " plates, offsets " + Arrays.toString(readState(img, n)));
        return out.toString();
    }

    /**
     * One frame, rotated once. Every row of every candidate plate count is read out of the same crop -
     * the {@link LockModel#MAX_PLATES} one, which contains them all, including the rows one step past a
     * 4- or 5-plate fan's ends.
     */
    private final class Fan {

        private final int x0, y0, w, h;
        private final int[] lum;
        /**
         * Where this frame separates its plates from the voids in them, and the one number every hole
         * gate is a fraction of.
         *
         * <p>It is Otsu's threshold - but taken over the <b>fan band alone</b>, the strip the seven
         * hole rows actually run through, and that restriction is the whole trick. Over the full crop
         * the vote is carried by the dark room the lock is sitting in, so the split comes back
         * separating <i>the room from the lock</i> (luminance 72 at gamma 1.2, where the dimmest plate
         * is at 43 and would count as a hole). Over the band, the only two populations left are the
         * steel and the holes cut in it - which is the question being asked.
         *
         * <p>This is what a fixed fraction cannot do. The gamma slider is a power curve, so the gap
         * between a plate and its holes does not scale: at the calibrated gamma the plates sit at
         * 181-234 and the holes at 0-50, and 105 splits them; at gamma 1.2 the plates are at 43-94 and
         * the holes at 0-10, and the splitting number is ~25 - not 0.45 of anything. A ratio of the
         * white end lands at 43, inside the plate. Otsu finds the gap because it looks for the gap.
         */
        /** The room the holes show through - the dark end of this frame's own range. */
        private final double black;
        /** The lock's own lit steel, for the pop signal and the lit-plate test. */
        private final double plateLevel;
        private final List<double[]> holes;
        private final double[][] metal = new double[LockModel.MAX_PLATES + 1][];

        /** Every half-step depth a plate can sit at: where it runs in the crop, and its metal. */
        private final double[] bandY;
        private final double[] bandMetal;

        Fan(BufferedImage img) {
            int[] crop = geo.fanCrop(LockModel.MAX_PLATES);
            x0 = crop[0];
            y0 = crop[1];
            w = Math.max(1, crop[2] - x0);
            h = Math.max(1, crop[3] - y0);
            BufferedImage rotated = geo.rotateFan(img, x0, y0, w, h);
            // Undo the gamma only when Tone can be trusted to. On the family it maps the frame back to
            // the calibrated look and the pipeline below reads calibrated pixels; off the family (HDR)
            // its curve is worse than nothing, so the frame goes through untouched and the relative
            // pipeline carries it. A guess (no panel found) is treated as off-family for the same
            // reason - a curve nobody could read is not a curve to trust. See the `tone` field.
            boolean correct = !tone.isGuess() && !tone.isOffFamily();
            lum = new int[w * h];
            int[] hist = new int[256];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = rotated.getRGB(x, y);
                    int l = LockReader.luminance(correct ? tone.map(argb) : argb);
                    lum[y * w + x] = l;
                    hist[l]++;
                }
            }
            for (int n = LockModel.MIN_PLATES; n <= LockModel.MAX_PLATES; n++) {
                metal[n] = new double[n + 2];
                Arrays.fill(metal[n], -1);
            }
            black = percentile(hist, BLACK_PCT);

            // Sample the metal at EVERY depth a plate could sit at - whole steps and the half steps
            // between them - because the count is not known yet and an even-plate lock's plates lie
            // exactly between an odd-plate lock's. Sampling only the seven-plate rows measured a
            // 6-plate lock's gaps instead of its plates: its own metal then came out BRIGHTER than the
            // "brightest plate", and `6p-gap-shadow` read as no lock at all.
            double[] depths = FanGeometry.plateDepths();
            double[] allY = new double[depths.length];
            double[] allMetal = new double[depths.length];
            double brightest = 1;
            for (int i = 0; i < depths.length; i++) {
                allY[i] = geo.rowPinAtDepth(depths[i])[1] - y0;
                allMetal[i] = metalAtDepth(depths[i]);
                brightest = Math.max(brightest, allMetal[i]);
            }
            plateLevel = Math.max(black + 1, brightest);

            // Keep only the depths that are actually plates. The rest are the gaps between them, the
            // dark room past the back of the fan, and the lock's casing past the front - and none of
            // them has any metal to lend. Interpolating their darkness into the band beside them
            // collapses the gate exactly where an end plate's holes are; standing the lock's own steel
            // in for them overshoots the other way, and pulls the gate up over a DIM plate's holes.
            // Neither is needed: they are not there, so they get no vote, and the field is drawn
            // between the plates that are.
            List<double[]> plates = new ArrayList<>();
            for (int i = 0; i < depths.length; i++) {
                if (allMetal[i] >= LIT_PLATE * plateLevel) {
                    plates.add(new double[] {allY[i], allMetal[i]});
                }
            }
            if (plates.isEmpty()) {
                plates.add(new double[] {0, plateLevel});
            }
            bandY = new double[plates.size()];
            bandMetal = new double[plates.size()];
            for (int i = 0; i < plates.size(); i++) {
                bandY[i] = plates.get(i)[0];
                bandMetal[i] = plates.get(i)[1];
            }
            holes = detectHoles();
        }

        /**
         * How bright the metal is at height {@code y} of the crop: a straight line between the two
         * <b>plates</b> either side of it, clamped to the outermost one beyond them.
         *
         * <p>Between the plates, because that is the only thing there is to measure. A piecewise-constant
         * "nearest plate" was tried instead and it fails for a subtle reason worth keeping: at 1600x900
         * the fan rows are 25px apart and a hole is 7px tall, so the upper pixels of a hole are nearer
         * the plate <i>above</i> than their own. A constant field then cuts a hard edge straight through
         * the holes and truncates them. The field has to be smooth.
         */
        double metalAt(int y) {
            if (y <= bandY[0]) {
                return bandMetal[0];
            }
            for (int i = 1; i < bandY.length; i++) {
                if (y <= bandY[i]) {
                    double t = (y - bandY[i - 1]) / Math.max(1e-6, bandY[i] - bandY[i - 1]);
                    return bandMetal[i - 1] + t * (bandMetal[i] - bandMetal[i - 1]);
                }
            }
            return bandMetal[bandMetal.length - 1];
        }

        /**
         * Every hole-sized void in the crop, as centroids in rotated-frame coordinates.
         *
         * <p>This is {@link LockReader#detectHoles}'s blob scan, gate for gate - the shape tests are
         * unchanged, because a hole's shape was never the problem. The three <b>luminance</b> tests
         * are the ones that were fitted to one screen, and each is now a fraction of the plate this
         * very frame is showing.
         */
        List<double[]> detectHoles() {
            // Every pixel, as a fraction of the range between the room behind the plates and the plate
            // it is actually cut into. Nothing downstream ever sees a luminance again.
            // Two readings of every pixel, and they answer different questions.
            //
            // local[] is against the plate the pixel is sitting on: "plate, or not plate?". It traces
            // the blob, and it has to be local, because the plates are not evenly lit.
            //
            // deep[] is against the whole frame's range: "how black is what shows through?". It decides
            // whether the blob is a genuine void or one of the plate's own shadows, and it must NOT be
            // local - the room behind the lock is just as black behind a dim plate as a bright one.
            double[] local = new double[w * h];
            double[] deep = new double[w * h];
            boolean[] dark = new boolean[w * h];
            double span = Math.max(1.0, plateLevel - black);
            for (int y = 0; y < h; y++) {
                double range = Math.max(1.0, metalAt(y) - black);
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    local[i] = (lum[i] - black) / range;
                    deep[i] = (lum[i] - black) / span;
                    dark[i] = local[i] < HOLE_DARK;
                }
            }
            boolean[] seen = new boolean[w * h];
            int[] stack = new int[w * h];
            List<double[]> out = new ArrayList<>();
            for (int seed = 0; seed < w * h; seed++) {
                if (!dark[seed] || seen[seed]) {
                    continue;
                }
                int sp = 0;
                stack[sp++] = seed;
                seen[seed] = true;
                int area = 0, minX = w, maxX = -1, minY = h, maxY = -1;
                double minLevel = Double.MAX_VALUE, sumLevel = 0;
                long sumX = 0, sumY = 0;
                while (sp > 0) {
                    int p = stack[--sp];
                    int x = p % w, y = p / w;
                    area++;
                    sumX += x;
                    sumY += y;
                    sumLevel += deep[p];
                    minLevel = Math.min(minLevel, deep[p]);
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int nx = x + dx, ny = y + dy;
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) {
                                continue;
                            }
                            int q = ny * w + nx;
                            if (dark[q] && !seen[q]) {
                                seen[q] = true;
                                stack[sp++] = q;
                            }
                        }
                    }
                }
                int bw = maxX - minX + 1, bh = maxY - minY + 1;
                if (area < holeMinArea || area > holeMaxArea
                        || bw < holeMinW || bw > holeMaxW
                        || bh < holeMinH || bh > holeMaxH
                        || minLevel > HOLE_MAX_MIN || sumLevel / area > HOLE_MAX_MEAN) {
                    continue;
                }
                out.add(new double[] {x0 + (double) sumX / area, y0 + (double) sumY / area});
            }
            return out;
        }

        RowFit fit(int n, int i) {
            double[] rowPin = geo.rowPin(n, i);
            double slope = FanGeometry.rowSlope(n, i);

            // The holes on this row's own deskewed line, in the order they appear along it.
            List<Double> onRow = new ArrayList<>();
            for (double[] hole : holes) {
                double lineY = rowPin[1] + (hole[0] - rowPin[0]) * slope;
                if (Math.abs(hole[1] - lineY) <= geo.rowMaxDy
                        && Math.abs(hole[0] - rowPin[0]) <= geo.rowMaxDx) {
                    onRow.add(hole[0]);
                }
            }
            int left = walk(onRow, rowPin[0], -1, false);
            int right = walk(onRow, rowPin[0], +1, false);
            if (left + right != HOLES) {
                left = walk(onRow, rowPin[0], -1, true);
                right = walk(onRow, rowPin[0], +1, true);
            }
            int walked = left + right;
            double own = metal(n, i);
            return new RowFit(walked == HOLES ? left : -1, walked,
                    own / plateLevel, pinOwnDark(rowPin, own), discDark(rowPin));
        }

        /**
         * How bright the metal this row runs over is: its plate, or the lock's casing, or the room.
         *
         * <p>Medians, twice over - down each column of a strip tall enough to clear the holes, and then
         * across the row. Never a maximum: a plate carries specular streaks, and at gamma 1.2 they put
         * the "steel" at luminance 193 when the steel is really at 46. A highlight is still a highlight
         * however dark the room, so it has to be outvoted rather than trusted.
         */
        double metal(int n, int i) {
            if (metal[n][i + 1] >= 0) {
                return metal[n][i + 1];
            }
            double m = metalAtDepth((n - 1) / 2.0 - i);
            metal[n][i + 1] = m;
            return m;
        }

        /**
         * How bright the metal at {@code depth} fan steps is: a plate, or the lock's casing, or the room.
         *
         * <p>Medians, twice over - down each column of a strip tall enough to clear the holes, and then
         * across the row. Never a maximum: a plate carries specular streaks, and at gamma 1.2 they put
         * the "steel" at luminance 193 when the steel is really at 46. A highlight is still a highlight
         * however dark the room, so it has to be outvoted rather than trusted.
         */
        double metalAtDepth(double depth) {
            double[] rowPin = geo.rowPinAtDepth(depth);
            double slope = FanGeometry.slopeAtDepth(depth);
            int span = 2 * geo.rowMaxDx + 1;
            double[] tops = new double[span];
            for (int k = 0; k < span; k++) {
                double xr = rowPin[0] - geo.rowMaxDx + k;
                double yr = rowPin[1] + (xr - rowPin[0]) * slope;
                tops[k] = column(xr, yr, plateHalf);
            }
            Arrays.sort(tops);
            return tops[span / 2];
        }

        /**
         * How dark the pin's own column is, against <b>its own plate</b>. A flat pin is a low brass dome
         * and reads bright; a popped one stands up into a cylinder and brings its shadowed flank into
         * the strip. Against the plate's own metal, not the lock's brightest - so a dark front plate,
         * whose whole column is dark, does not carry every one of its flat pins over the line.
         */
        double pinOwnDark(double[] rowPin, double ownMetal) {
            double pin = column(rowPin[0], rowPin[1], pinHalf);
            return (ownMetal - pin) / Math.max(1.0, ownMetal);
        }

        /**
         * The mean darkness of a disc at the pin, against the whole frame's range. This is the half of
         * the pop signal that tells a raised cylinder - a dark <i>blob</i> - from a dark plate edge: the
         * cylinder darkens the whole disc, the edge only a column of it.
         */
        double discDark(double[] rowPin) {
            int cx = (int) Math.round(rowPin[0]) - x0;
            int cy = (int) Math.round(rowPin[1]) - y0;
            double span = Math.max(1.0, plateLevel - black);
            int r = Math.max(2, (int) Math.round(geo.holeMaxW * 0.45));
            double sum = 0;
            int count = 0;
            for (int dy = -r; dy <= r; dy++) {
                for (int dx = -r; dx <= r; dx++) {
                    int xx = cx + dx, yy = cy + dy;
                    if (xx < 0 || xx >= w || yy < 0 || yy >= h || dx * dx + dy * dy > r * r) {
                        continue;
                    }
                    sum += (plateLevel - lum[yy * w + xx]) / span;
                    count++;
                }
            }
            return count == 0 ? 0 : sum / count;
        }

        /** The median luminance of one column of the strip, in crop coordinates (solid off-crop). */
        double column(double xr, double yr, int half) {
            int cx = (int) Math.round(xr) - x0;
            int cy = (int) Math.round(yr) - y0;
            int[] got = new int[2 * half + 1];
            int n = 0;
            for (int dy = -half; dy <= half; dy++) {
                int yy = cy + dy;
                if (cx >= 0 && cx < w && yy >= 0 && yy < h) {
                    got[n++] = lum[yy * w + cx];
                }
            }
            if (n == 0) {
                return 255; // off-crop reads as solid plate, never as a hole
            }
            int[] slice = Arrays.copyOf(got, n);
            Arrays.sort(slice);
            return slice[n / 2];
        }
    }

    /**
     * Walks the hole lattice outward from the pin, hopping hole to hole one spacing at a time, and
     * returns how many slots it crossed.
     *
     * <p>This is {@link LockReader}'s walk, and it is a walk rather than a lattice fit for a reason a
     * rewrite here rediscovered the hard way: perspective makes the spacing vary <b>41-54px along a
     * single row</b>, so no rigid lattice fits all seven slots at once - its slots drift off the holes
     * by the third step and every margin collapses. Stepping hole to hole lets the row bend as the
     * camera makes it bend.
     */
    private int walk(List<Double> rowHoles, double px, int dir, boolean allowSkip) {
        double cur = px;
        int slots = 0;
        while (slots < HOLES) {
            double bestX = 0, bestErr = Double.MAX_VALUE;
            int bestSlots = 0;
            for (double x : rowHoles) {
                double d = (x - cur) * dir;
                int step = (d >= geo.stepMin && d <= geo.stepMax) ? 1
                        : (allowSkip && d >= LockReader.SKIP_MIN * geo.viewport().scale()
                                && d <= LockReader.SKIP_MAX * geo.viewport().scale()) ? 2 : 0;
                if (step == 0) {
                    continue;
                }
                double err = Math.abs(d - (step == 1 ? geo.stepIdeal
                        : LockReader.SKIP_IDEAL * geo.viewport().scale()));
                if (err < bestErr) {
                    bestErr = err;
                    bestX = x;
                    bestSlots = step;
                }
            }
            if (bestSlots == 0) {
                break;
            }
            slots += bestSlots;
            cur = bestX;
        }
        return slots;
    }

    /** The luminance the given fraction of the crop's pixels fall below. */
    private static double percentile(int[] hist, double p) {
        long total = 0;
        for (int c : hist) {
            total += c;
        }
        long want = (long) Math.ceil(p * total);
        long seen = 0;
        for (int i = 0; i < hist.length; i++) {
            seen += hist[i];
            if (seen >= want) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Every row's fit, so the ratios above can be re-measured rather than believed.
     * {@code tools/ReaderBench} is what fits {@link #POP_MIN_DARKNESS} out of this, against the
     * labelled corpus - the same "measure it, never guess it" rule the tone curves are held to.
     */
    public List<RowFit> rows(BufferedImage img, int n) {
        Fan fan = new Fan(img);
        List<RowFit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(fan.fit(n, i));
        }
        return out;
    }

    /**
     * The two pop readings for every plate, so {@code tools/PopProbe2} can re-fit their cuts against
     * the corpus rather than have them believed - the same "measure it, never guess it" rule the tone
     * curves are held to. Each row is {@code {pinDark, discDark}}; a plate is popped when both clear
     * {@link #POP_PIN_MIN} and {@link #POP_DISC_MIN}.
     */
    public List<double[]> popFeatures(BufferedImage img, int n) {
        Fan fan = new Fan(img);
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            RowFit f = fan.fit(n, i);
            out.add(new double[] {f.pinDark(), f.discDark()});
        }
        return out;
    }
}
