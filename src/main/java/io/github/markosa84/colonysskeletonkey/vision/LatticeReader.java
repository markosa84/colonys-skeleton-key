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
 * fitted on one screen at one gamma: {@code isPin}'s {@code r >= 130} and the hole scan's
 * {@code luminance < 105}. {@link Tone} was built to undo the gamma
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
 * {@code LatticeReaderTest}, it matches {@link LockReader} on <b>every</b> labelled frame - 162/162
 * plate counts, 865/865 offsets, across the 53-frame 4K census, the whole gamma slider and all 19
 * resolution sweep modes - and on top of that it reads the reporters' HDR dumps, where
 * {@code LockReader} returns -1 and the player is told "no lock detected". {@code LockReader} is kept
 * as the reference (and for its {@code luminance} helper and the constants this class borrows), behind
 * {@code --reader=legacy}.
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

    /** Half-height of the strip a plate's metal is read in - tall enough to clear the hole row. */
    private static final double PLATE_HALF_HEIGHT = 0.60;

    private final FanGeometry geo;
    private final double holeMinArea, holeMaxArea;
    private final double holeMinW, holeMaxW, holeMinH, holeMaxH;
    private final int plateHalf;

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
        this(viewport.mapping(), tone);
    }

    /** Against a mapping directly, however it was arrived at. See {@link FanGeometry}'s. */
    public LatticeReader(ViewMapping mapping, Tone tone) {
        this.tone = tone;
        this.geo = new FanGeometry(mapping);
        // Areas are the one thing that DOES scale with the view, and quadratically: a hole is a
        // hole, but it is fewer pixels on a smaller screen. The slack is because these blobs are traced
        // at a different threshold than the one the bounds were measured at - see SHAPE_SLACK.
        this.holeMinArea = mapping.area(LockReader.HOLE_MIN_AREA) / (SHAPE_SLACK * SHAPE_SLACK);
        this.holeMaxArea = mapping.area(LockReader.HOLE_MAX_AREA) * SHAPE_SLACK * SHAPE_SLACK;
        this.holeMinW = geo.holeMinW / SHAPE_SLACK;
        this.holeMaxW = geo.holeMaxW * SHAPE_SLACK;
        this.holeMinH = geo.holeMinH / SHAPE_SLACK;
        this.holeMaxH = geo.holeMaxH * SHAPE_SLACK;
        this.plateHalf = Math.max(2, (int) Math.round(geo.holeMaxH * PLATE_HALF_HEIGHT));
    }

    /**
     * What one row came to.
     *
     * @param slot   which of the 7 lattice positions holds the pin, or -1 if the row did not add up
     * @param walked how many hole slots the walk crossed, out of six
     * @param lit    this row's metal, against the brightest plate of the same lock
     */
    public record RowFit(int slot, int walked, double lit) {

        /**
         * A plate is a row of six holes in lit steel - which nothing else in a room is. The
         * {@code lit} guard is what rejects the dark front casing and the room past the back of the
         * fan: both can fake a hole or two, but neither is lit steel.
         */
        public boolean isPlate() {
            return walked == HOLES && lit >= LIT_PLATE;
        }

        public int offset() {
            return walked == HOLES ? slot - LockModel.MAX_OFFSET : UNKNOWN;
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
            out[i] = fan.fit(n, i).offset();
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
                out.append(String.format(Locale.ROOT, " [%d: %d/6 holes, lit %.2f%s]",
                        i, f.walked(), f.lit(), f.isPlate() ? "" : " NOT A ROW"));
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
            return new RowFit(walked == HOLES ? left : -1, walked, own / plateLevel);
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
        double skipMin = geo.mapping().len(LockReader.SKIP_MIN);
        double skipMax = geo.mapping().len(LockReader.SKIP_MAX);
        double skipIdeal = geo.mapping().len(LockReader.SKIP_IDEAL);
        while (slots < HOLES) {
            double bestX = 0, bestErr = Double.MAX_VALUE;
            int bestSlots = 0;
            for (double x : rowHoles) {
                double d = (x - cur) * dir;
                int step = (d >= geo.stepMin && d <= geo.stepMax) ? 1
                        : (allowSkip && d >= skipMin && d <= skipMax) ? 2 : 0;
                if (step == 0) {
                    continue;
                }
                double err = Math.abs(d - (step == 1 ? geo.stepIdeal : skipIdeal));
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
     * Every row's fit, so the geometry ratios can be re-measured rather than believed - what
     * {@code tools/ReaderBench} scores against the labelled corpus, the same "measure it, never guess
     * it" rule the tone curves are held to.
     */
    public List<RowFit> rows(BufferedImage img, int n) {
        Fan fan = new Fan(img);
        List<RowFit> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(fan.fit(n, i));
        }
        return out;
    }
}
