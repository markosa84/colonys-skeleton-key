package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.Locale;

/**
 * The game's gamma, measured off the frame and undone.
 *
 * <p>Every colour and luminance gate in {@link LockReader} is an absolute number fitted at the
 * gamma the calibration frames were shot at. The game's gamma slider runs <b>1.2 to 3.2</b> and is a
 * matter of taste, so those numbers describe one player's pixels and nobody else's. Both ends break
 * the reader, from opposite directions:
 *
 * <ul>
 *   <li><b>Dark end.</b> The brass falls under {@code isPin}'s {@code r >= 130}. At 1.2 the pin box
 *       holds a few dozen warm pixels where the calibration has 1519 - not enough for even one blob,
 *       so no fan fits and the tool reports "no lock detected" over a screenshot that looks
 *       perfectly fine. This is what a user actually reported.</li>
 *   <li><b>Bright end.</b> The brass highlights climb over {@code b <= 140}, shrinking the pin blobs
 *       (at 3.2 they measure 51-103px against a calibrated 150-180), and real holes brighten past
 *       {@code HOLE_MAX_MIN_LUM} so the hole rows stop reading - at 3.2 the reader could not resolve
 *       a plate's offset at all.</li>
 * </ul>
 *
 * <p>So the frame is mapped back to the calibrated look before the reader ever sees it, and every
 * measured constant keeps its meaning. There is deliberately no {@code -Dlockpick.gamma}: the game
 * carries a second knob (<code>m_ColorBrightnessOffset</code>) beside <code>m_Gamma</code>, so only
 * the rendered result is the truth - and a setting a player can change is a setting that goes stale.
 * Observe, don't configure, exactly as with {@code Skill}.
 *
 * <h2>The probe</h2>
 * The <b>lockpick-counter panel</b> ({@code GameScreen.picksBox}). It is opaque UI at a fixed screen
 * position, so the room cannot touch it, but the gamma pass runs over it like everything else.
 * Measured across 8 fixtures - four rooms, 4/5/6/7-plate locks, different pick counts - and at all
 * 23 supported resolutions down to 800x600, its histogram is the same every time: an <b>ink</b>
 * plateau (the digits) and a <b>white</b> plateau (the panel). Two numbers, and the room contributes
 * neither.
 *
 * <p>The two anchors are <b>complementary</b>, which is what lets one probe span the whole slider:
 *
 * <pre>
 *   gamma    1.2   1.5   1.8   2.1   2.4  [2.7]  3.0   3.2
 *   ink        0     9    24    41    58    74    91   100     &lt;- crushed to black at the dark end
 *   white    244   253   255   255   255   255   255   255     &lt;- clipped from 1.8 up
 * </pre>
 *
 * Exactly where the ink dies, the white lifts off 255 and takes over. So: index the family by ink,
 * except at the bottom of the slider, where white does it.
 *
 * <h2>The curves</h2>
 * <b>Measured, not fitted.</b> A parametric fit was tried first - a two-parameter {@code gain x
 * power} curve through the panel's dark anchors - and it fails, badly: extrapolated to the bright
 * end it mapped observed 255 back to 179, crushed the pin highlights, and left <i>one</i> detected
 * pin where the raw frame had seven. Two closely-spaced dark anchors say nothing about the far end
 * of the range. Do not try it again.
 *
 * <p>Instead each curve below is the real transfer function, recovered from a matched pair of frames
 * (the same lock in the same state, one at this gamma and one at the calibrated 2.7): every pixel is
 * an {@code (observed, calibrated)} sample, and the median per level <i>is</i> the curve. The
 * transform is a plain per-pixel LUT on the final frame - verified, per channel, with an
 * inter-quartile spread of 0-3 levels - so a LUT is exactly what inverts it. {@code tools/ToneTable.java}
 * regenerates this table from the fixtures.
 *
 * <p>What normalisation cannot do is put back what the game threw away. At 3.2 the highlights clip;
 * at 1.2 the whole calibrated range 0-255 is squeezed into observed 0-128, and its bottom third into
 * six observed levels. Both still read correctly - pin blobs land within ~2% of the reference and
 * the popped pin returns to its band - but the margins are thinnest there, which is what the
 * {@code gamma-*} fixtures exist to pin.
 */
public final class Tone {

    /** What the probe reads on a frame at the gamma the reader was calibrated at. */
    static final int CALIBRATED_INK = 74;
    static final int CALIBRATED_WHITE = 255;

    /** Sentinel for "the panel was not there to read". */
    private static final int NOT_PROBED = -1;

    /**
     * How bright the panel's background must be before we believe it is the panel at all. The
     * darkest setting measured leaves it at 244, and a reported frame at 199; an empty box - which
     * is what a wrong viewport hands over - is black. Anywhere between is a safe place to draw the
     * line, and lower is safer than higher: refusing a real panel loses the correction entirely.
     */
    private static final int PANEL_MIN_WHITE = 128;

    /**
     * How far the panel's white may sit from what the family expects at that ink before the frame is
     * called out as something the gamma slider alone did not do. The measured whites are 253-255
     * across everything above the dark end, so anything like this far off is a different setting, not
     * noise.
     */
    private static final int OFF_FAMILY = 24;

    /**
     * How far below the white the ink must be sought, so the panel's own antialiasing does not get a
     * vote. The two plateaus are never closer than this: the tightest measured pair is HDR's 36 and
     * 199, and the tightest gamma setting's is 100 and 255.
     */
    private static final int INK_GAP = 40;

    /**
     * Nothing to correct: the identity. This is what every existing test gets, and what a frame at
     * the calibrated gamma resolves to - so the 189-frame corpus keeps vouching for the reader's
     * constants unchanged.
     */
    public static final Tone CALIBRATED = new Tone(identity(), CALIBRATED_INK, CALIBRATED_WHITE);

    /** What the tool falls back to when it cannot find the panel: the calibrated tone, and a warning. */
    public static final Tone UNREADABLE = new Tone(identity(), NOT_PROBED, NOT_PROBED);

    /** observed level -> calibrated level, applied to each channel. */
    private final int[] lut;
    private final int ink;
    private final int white;

    private Tone(int[] lut, int ink, int white) {
        this.lut = lut;
        this.ink = ink;
        this.white = white;
    }

    /**
     * Reads the panel and returns the tone to undo. {@link #UNREADABLE} (the identity) when the
     * panel is not where the viewport says it should be - which is itself a loud hint that the
     * <b>viewport</b> is wrong, the other way this tool has failed in the field.
     */
    public static Tone estimate(BufferedImage frame, Viewport viewport) {
        return estimate(frame, viewport.mapping());
    }

    /**
     * The same, against a mapping - which is what a corrected one must be re-estimated through. The
     * probe is a box at a fixed <i>reference</i> position, so a mapping that moves the lock moves the
     * panel with it; reading the gamma through the old one would probe whatever now sits there.
     */
    public static Tone estimate(BufferedImage frame, ViewMapping mapping) {
        int[] histogram = panel(frame, GameScreen.picksBox(mapping));
        if (histogram == null) {
            return UNREADABLE;
        }
        int white = white(histogram);
        int ink = ink(histogram, white);
        if (!plausible(histogram, ink, white)) {
            return UNREADABLE;
        }
        return new Tone(curve(ink, white), ink, white);
    }

    /**
     * The panel's background: the biggest plateau in the box, whatever level the gamma has put it
     * at - which is why it is the mode of the whole histogram rather than of some assumed upper
     * band. (It used to be sought above 128, which quietly assumed the ink stays below that. The ink
     * climbs with the gamma: it is already at 100 at the top of the slider.)
     */
    private static int white(int[] histogram) {
        return mode(histogram, 0, 256);
    }

    /** The digits: the biggest thing well below the white. See {@link #INK_GAP}. */
    private static int ink(int[] histogram, int white) {
        return mode(histogram, 0, Math.max(1, white - INK_GAP));
    }

    /**
     * Where this panel separates its <b>digits</b> from the box behind them - the midpoint of its two
     * plateaus - or -1 when what is in the box is not a panel at all.
     *
     * <p>Shared with {@link GameScreen#pickCounterFingerprint}, which has to split the same box for a
     * different reason: this class indexes the gamma family by the two plateaus, and the fingerprint
     * thresholds between them. Neither can use an absolute number - the plateaus move together with
     * the slider, from 0/244 at gamma 1.2 to 100/255 at 3.2, and an HDR tonemap puts them somewhere
     * the slider cannot reach at all (11-36 / 183-199). Measured across all of that they are never
     * closer than <b>155</b> levels, so their midpoint is a wide and stable place to cut.
     */
    static int panelSplit(int[] histogram) {
        int white = white(histogram);
        int ink = ink(histogram, white);
        return plausible(histogram, ink, white) ? (ink + white) / 2 : -1;
    }

    /**
     * The panel must actually look like the panel: a bright plateau, an ink plateau well below it,
     * and enough pixels in each to be a plateau rather than a stray. A tone estimated from something
     * that is <i>not</i> the panel would be worse than none - a wrong curve makes the reader
     * confidently wrong, and a false pin-pop tells the session a plate is centred when it is not.
     *
     * <p>{@link #PANEL_MIN_WHITE} is what separates a real panel from an empty box, and it is
     * deliberately not tight. It once sat at 200, on the reasoning that the panel is white and stays
     * white - and it <b>rejected the very frames this whole change exists for</b>: the reporter's
     * panel measures <b>199</b>. One level. The guard is here to notice a box with no panel in it (a
     * wrong viewport leaves it black), not to have opinions about how dark a player's game may be.
     */
    private static boolean plausible(int[] histogram, int ink, int white) {
        int total = 0;
        for (int count : histogram) {
            total += count;
        }
        int floor = Math.max(4, total / 50); // a plateau is a share of the box, not a handful
        return white >= PANEL_MIN_WHITE && histogram[white] >= floor && histogram[ink] >= floor;
    }

    /** True when there is nothing to undo. */
    public boolean isCalibrated() {
        return ink == CALIBRATED_INK && white == CALIBRATED_WHITE;
    }

    /** True when the panel could not be read at all, and the calibrated tone is a guess. */
    public boolean isGuess() {
        return ink == NOT_PROBED;
    }

    /**
     * True when this frame is darker (or brighter) than the gamma slider alone can explain: the
     * panel's white sits more than {@link #OFF_FAMILY} levels from where its ink says it should. The
     * family is one-dimensional - gamma only - so it cannot map such a frame back to the calibrated
     * look, and {@link #map} applies the nearest curve knowing it may be "worse than nothing" (see the
     * class doc's HDR note). It is here so a reader can take a tone-free path instead of trusting a
     * curve that does not fit - which is exactly what {@link LatticeReader} does with it.
     */
    public boolean isOffFamily() {
        int expected = expectedWhite(ink);
        return expected >= 0 && Math.abs(white - expected) > OFF_FAMILY;
    }

    /** What the probe read off the panel. For the tests, and for a bug report. */
    int ink() {
        return ink;
    }

    int white() {
        return white;
    }

    /** The calibrated level an observed one maps back to. For the tests. */
    int level(int observed) {
        return lut[observed];
    }

    /** Maps one packed pixel back to the calibrated look, channel by channel. */
    int map(int argb) {
        return (lut[(argb >> 16) & 0xFF] << 16)
                | (lut[(argb >> 8) & 0xFF] << 8)
                | lut[argb & 0xFF];
    }

    /** What the probe read, for the banner and for every dump's sidecar. */
    public String describe() {
        if (ink == NOT_PROBED) {
            return "tone:    the lockpick counter is not where the viewport says it is, so the "
                    + "gamma could not be read. Assuming the calibrated one. (If no lock is found "
                    + "either, suspect the viewport, not the colours.)";
        }
        if (isCalibrated()) {
            return "tone:    panel ink " + ink + " - the gamma the reader was calibrated at. "
                    + "Nothing to undo.";
        }
        // Ink alone says which way: it rises with the gamma. The white cannot - it only moves at the
        // dark end, where it lifts off its clip as everything else falls.
        String read = String.format(Locale.ROOT,
                "tone:    panel ink %d, white %d (calibrated: %d, %d) - the game's gamma is %s than "
                + "the calibration, so every frame is mapped back to it.",
                ink, white, CALIBRATED_INK, CALIBRATED_WHITE,
                ink > CALIBRATED_INK ? "brighter" : "darker");
        if (isOffFamily()) {
            int expected = expectedWhite(ink);
            read += String.format(Locale.ROOT,
                    "%n         NOTE: at ink %d the panel's white should be about %d, not %d. This "
                    + "frame is %s than the gamma slider alone can make it, so something else is "
                    + "moving the picture too - the game's brightness offset, HDR, a display "
                    + "profile. The nearest measured correction is applied, and it may not be "
                    + "enough.", ink, expected, white, white < expected ? "darker" : "brighter");
        }
        return read;
    }

    // --- the probe -------------------------------------------------------------------------------

    /** Luminance histogram of the panel, or null if the box is not wholly inside the frame. */
    private static int[] panel(BufferedImage frame, Rectangle box) {
        if (!new Rectangle(0, 0, frame.getWidth(), frame.getHeight()).contains(box)) {
            return null;
        }
        int[] histogram = new int[256];
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                histogram[LockReader.luminance(frame.getRGB(x, y))]++;
            }
        }
        return histogram;
    }

    /** Luminance histogram of a whole image - what {@link GameScreen} grabs the panel as. */
    static int[] histogram(BufferedImage img) {
        int[] histogram = new int[256];
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                histogram[LockReader.luminance(img.getRGB(x, y))]++;
            }
        }
        return histogram;
    }

    private static int mode(int[] histogram, int from, int to) {
        int mode = 0;
        int best = 0;
        for (int v = from; v < Math.min(to, 256); v++) {
            if (histogram[v] > best) {
                best = histogram[v];
                mode = v;
            }
        }
        return mode;
    }

    // --- the family ------------------------------------------------------------------------------

    /**
     * The curve for a probe reading, interpolated between the two measured members it falls
     * between.
     *
     * <p>Ink indexes the family - it is monotone across the slider and readable at every
     * resolution - <b>except</b> below the second member, where the game has crushed the ink to
     * black and it can no longer tell one setting from another. There the panel's white, which has
     * just lifted off its clip, indexes instead.
     */
    private static int[] curve(int ink, int white) {
        Curve dark = FAMILY[0];
        Curve next = FAMILY[1];
        if (ink < next.ink()) {
            return blend(dark.lut(), next.lut(), fraction(white, dark.white(), next.white()));
        }
        for (int i = 1; i < FAMILY.length - 1; i++) {
            if (ink < FAMILY[i + 1].ink()) {
                return blend(FAMILY[i].lut(), FAMILY[i + 1].lut(),
                        fraction(ink, FAMILY[i].ink(), FAMILY[i + 1].ink()));
            }
        }
        return FAMILY[FAMILY.length - 1].lut();
    }

    /**
     * The white the family expects to see beside this ink, or -1 at the dark end (where the white is
     * itself the index and there is nothing to cross-check it against).
     *
     * <p>The two anchors are not independent - the slider moves both - so a pair that is not on the
     * measured curve is a frame the <b>gamma slider alone cannot produce</b>. That is worth saying
     * out loud rather than quietly under-correcting: the frames from the user who reported this bug
     * read ink 13 with a white of 184, where the family says 254. Something beyond {@code m_Gamma}
     * is dimming their picture, and the honest thing is to apply the nearest measured curve and
     * admit it may not be enough - not to extrapolate, which is how the first attempt at this
     * destroyed the pins.
     */
    private static int expectedWhite(int ink) {
        if (ink < FAMILY[1].ink()) {
            return -1;
        }
        for (int i = 1; i < FAMILY.length - 1; i++) {
            if (ink < FAMILY[i + 1].ink()) {
                double t = fraction(ink, FAMILY[i].ink(), FAMILY[i + 1].ink());
                return (int) Math.round(
                        FAMILY[i].white() + (FAMILY[i + 1].white() - FAMILY[i].white()) * t);
            }
        }
        return FAMILY[FAMILY.length - 1].white();
    }

    /** Where {@code v} sits between two anchors, clamped to [0, 1]. */
    private static double fraction(int v, int lo, int hi) {
        if (lo == hi) {
            return 0;
        }
        return Math.clamp((v - lo) / (double) (hi - lo), 0.0, 1.0);
    }

    /** A linear blend of two monotone curves is monotone, which is all the reader asks of one. */
    private static int[] blend(int[] a, int[] b, double t) {
        int[] out = new int[256];
        for (int v = 0; v < 256; v++) {
            out[v] = (int) Math.round(a[v] + (b[v] - a[v]) * t);
        }
        return out;
    }

    private static int[] identity() {
        int[] lut = new int[256];
        for (int v = 0; v < 256; v++) {
            lut[v] = v;
        }
        return lut;
    }

    /** One measured member of the family: what the probe reads, and the curve that undoes it. */
    private record Curve(int ink, int white, int[] lut) {
        Curve(int ink, int white, String levels) {
            this(ink, white, parse(levels));
        }

        private static int[] parse(String levels) {
            String[] fields = levels.trim().split("\\s*,\\s*");
            if (fields.length != 256) {
                throw new IllegalStateException("a tone curve is 256 levels, not " + fields.length);
            }
            int[] lut = new int[256];
            for (int v = 0; v < 256; v++) {
                lut[v] = Integer.parseInt(fields[v].trim());
            }
            return lut;
        }
    }

    /**
     * The measured family, in slider order. Each curve maps that gamma's observed levels back to the
     * calibrated ones; the calibrated member is the identity, so a player at 2.7 is not touched at
     * all. Regenerate with {@code tools/ToneTable.java} - never hand-edit.
     */
    private static final Curve[] FAMILY = {
        // gamma 1.2. The whole calibrated range is squeezed into observed 0..128, and its bottom
        // third into six levels - this is the curve with the least to work with, by far.
        new Curve(0, 244, """
                   0,   3,  20,  39,  58,  77,  97, 101, 104, 107, 110, 113, 116, 118, 120, 122,
                 124, 126, 128, 130, 131, 133, 135, 137, 139, 141, 143, 145, 146, 148, 150, 151,
                 153, 154, 156, 157, 159, 160, 162, 164, 166, 167, 168, 170, 171, 172, 174, 175,
                 177, 178, 179, 180, 182, 183, 184, 186, 187, 188, 190, 191, 192, 193, 194, 195,
                 197, 198, 199, 201, 201, 203, 204, 205, 206, 207, 208, 209, 210, 212, 213, 214,
                 215, 216, 217, 218, 219, 220, 222, 222, 223, 224, 225, 226, 227, 229, 229, 230,
                 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242, 243, 244, 245, 246,
                 247, 248, 249, 250, 250, 251, 252, 253, 254, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
                """),
        // gamma 1.5
        new Curve(9, 253, """
                   0,   3,  14,  26,  38,  51,  65,  68,  70,  72,  74,  77,  79,  81,  84,  86,
                  87,  89,  91,  93,  95,  97,  98, 100, 102, 104, 105, 107, 109, 110, 112, 113,
                 115, 117, 118, 119, 121, 122, 123, 125, 127, 129, 130, 131, 133, 134, 136, 137,
                 139, 140, 141, 142, 144, 145, 146, 147, 149, 150, 152, 153, 154, 155, 156, 157,
                 159, 160, 161, 163, 163, 165, 166, 168, 169, 170, 171, 172, 173, 174, 175, 176,
                 177, 179, 180, 181, 182, 183, 184, 185, 187, 188, 189, 190, 191, 192, 193, 194,
                 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210,
                 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226,
                 227, 228, 229, 230, 231, 232, 233, 233, 234, 235, 236, 237, 238, 239, 240, 241,
                 242, 243, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 253, 254, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
                """),
        // gamma 1.8
        new Curve(24, 255, """
                   0,   3,   3,   4,   5,   6,   7,   8,   9,  18,  27,  36,  46,  55,  57,  58,
                  60,  62,  64,  66,  68,  69,  71,  72,  74,  75,  77,  78,  80,  82,  83,  84,
                  86,  87,  89,  90,  91,  93,  94,  96,  97,  99,  99, 101, 102, 104, 105, 107,
                 108, 109, 111, 112, 113, 114, 115, 116, 117, 118, 120, 121, 122, 123, 125, 126,
                 128, 129, 130, 132, 132, 133, 135, 136, 137, 138, 140, 140, 142, 143, 144, 145,
                 146, 147, 148, 150, 151, 152, 153, 154, 156, 157, 158, 159, 160, 161, 162, 163,
                 164, 165, 166, 167, 168, 169, 170, 172, 173, 174, 175, 176, 177, 178, 179, 180,
                 181, 182, 183, 184, 185, 186, 187, 189, 189, 190, 191, 192, 193, 194, 195, 196,
                 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207, 208, 209, 210, 211, 212,
                 213, 214, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226, 227,
                 228, 229, 230, 231, 232, 232, 233, 234, 235, 236, 237, 238, 238, 239, 240, 241,
                 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 252, 253, 254, 254, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
                """),
        // gamma 2.1
        new Curve(41, 255, """
                   0,   3,   4,   5,   6,   6,   7,   8,   9,  10,  11,  12,  12,  13,  14,  20,
                  27,  33,  40,  46,  47,  49,  50,  51,  52,  54,  55,  56,  57,  59,  60,  61,
                  63,  64,  66,  67,  68,  70,  71,  72,  74,  75,  76,  77,  78,  80,  81,  82,
                  83,  84,  85,  87,  88,  89,  90,  91,  92,  94,  95,  97,  98,  99, 100, 101,
                 102, 103, 104, 106, 107, 108, 109, 111, 112, 113, 114, 114, 116, 117, 118, 119,
                 120, 121, 122, 123, 124, 126, 127, 128, 130, 131, 132, 133, 134, 135, 135, 137,
                 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 154,
                 155, 156, 157, 158, 159, 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170,
                 171, 172, 173, 174, 175, 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186,
                 187, 188, 189, 190, 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202,
                 203, 204, 205, 206, 206, 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217,
                 218, 219, 220, 221, 222, 223, 224, 225, 226, 227, 227, 228, 229, 230, 231, 232,
                 233, 234, 235, 236, 237, 238, 238, 239, 240, 241, 242, 243, 244, 245, 246, 247,
                 248, 249, 250, 251, 252, 253, 253, 254, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
                """),
        // gamma 2.4
        new Curve(58, 255, """
                   0,   3,   4,   5,   6,   7,   8,   9,  10,  11,  12,  13,  13,  14,  15,  16,
                  17,  18,  21,  23,  26,  29,  32,  34,  35,  37,  38,  39,  41,  42,  43,  45,
                  46,  47,  48,  49,  50,  51,  52,  53,  55,  56,  57,  59,  60,  61,  62,  63,
                  64,  65,  66,  67,  68,  69,  70,  71,  72,  74,  75,  76,  77,  78,  79,  80,
                  81,  82,  83,  85,  85,  87,  88,  89,  90,  91,  92,  93,  94,  95,  97,  98,
                  99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114,
                 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130,
                 132, 132, 133, 134, 135, 136, 137, 139, 140, 141, 142, 143, 144, 145, 147, 147,
                 148, 149, 150, 151, 152, 154, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163,
                 164, 166, 166, 168, 169, 170, 171, 172, 173, 173, 174, 175, 176, 177, 178, 180,
                 180, 181, 182, 183, 184, 185, 187, 187, 188, 189, 190, 191, 192, 193, 194, 195,
                 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 205, 206, 207, 208, 209, 210,
                 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223, 224, 225, 226,
                 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239, 240, 241, 242,
                 243, 244, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255, 255, 255,
                 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255, 255
                """),
        // gamma 2.7 - THE CALIBRATION. Measured off a fresh capture and it came back the identity to
        // within a level across the whole threshold band, so it is written as the exact identity:
        // at this gamma there is nothing to undo, and the 189-frame corpus must read bit-for-bit as
        // it always has.
        new Curve(CALIBRATED_INK, CALIBRATED_WHITE, """
                   0,   1,   2,   3,   4,   5,   6,   7,   8,   9,  10,  11,  12,  13,  14,  15,
                  16,  17,  18,  19,  20,  21,  22,  23,  24,  25,  26,  27,  28,  29,  30,  31,
                  32,  33,  34,  35,  36,  37,  38,  39,  40,  41,  42,  43,  44,  45,  46,  47,
                  48,  49,  50,  51,  52,  53,  54,  55,  56,  57,  58,  59,  60,  61,  62,  63,
                  64,  65,  66,  67,  68,  69,  70,  71,  72,  73,  74,  75,  76,  77,  78,  79,
                  80,  81,  82,  83,  84,  85,  86,  87,  88,  89,  90,  91,  92,  93,  94,  95,
                  96,  97,  98,  99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111,
                 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127,
                 128, 129, 130, 131, 132, 133, 134, 135, 136, 137, 138, 139, 140, 141, 142, 143,
                 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157, 158, 159,
                 160, 161, 162, 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175,
                 176, 177, 178, 179, 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191,
                 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206, 207,
                 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222, 223,
                 224, 225, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 239,
                 240, 241, 242, 243, 244, 245, 246, 247, 248, 249, 250, 251, 252, 253, 254, 255
                """),
        // gamma 3.0
        new Curve(91, 255, """
                   0,   3,   3,   5,   5,   6,   7,   8,   9,  10,  11,  12,  12,  13,  14,  15,
                  16,  16,  17,  18,  19,  19,  20,  21,  22,  23,  23,  24,  25,  25,  26,  27,
                  27,  28,  29,  30,  31,  32,  33,  34,  34,  34,  35,  37,  38,  40,  41,  41,
                  41,  41,  41,  41,  41,  41,  42,  42,  43,  43,  44,  45,  46,  47,  48,  49,
                  50,  51,  52,  53,  54,  55,  56,  56,  57,  58,  59,  60,  61,  62,  63,  64,
                  64,  66,  67,  68,  69,  70,  71,  71,  72,  73,  74,  75,  76,  77,  78,  79,
                  80,  81,  82,  83,  84,  84,  85,  86,  87,  88,  89,  90,  91,  92,  93,  94,
                  95,  96,  97,  98,  99, 100, 101, 102, 102, 104, 105, 106, 107, 108, 109, 109,
                 111, 112, 113, 114, 115, 116, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125,
                 126, 127, 128, 129, 130, 131, 132, 133, 135, 135, 136, 137, 138, 139, 140, 142,
                 142, 143, 144, 145, 146, 147, 148, 149, 150, 151, 152, 153, 154, 155, 156, 157,
                 158, 159, 160, 161, 162, 163, 164, 165, 166, 168, 168, 169, 170, 171, 172, 173,
                 175, 175, 176, 177, 178, 179, 180, 182, 182, 183, 184, 185, 186, 187, 189, 190,
                 191, 192, 193, 194, 195, 196, 197, 198, 199, 200, 201, 202, 203, 204, 205, 206,
                 207, 208, 209, 210, 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221, 222,
                 223, 224, 226, 227, 228, 229, 230, 231, 232, 233, 234, 235, 236, 240, 241, 255
                """),
        // gamma 3.2 - the top of the slider, where the highlights clip and cannot be recovered.
        new Curve(100, 255, """
                   0,   4,   4,   6,   7,   8,   9,  10,  10,  11,  12,  13,  14,  14,  15,  16,
                  17,  17,  18,  19,  20,  21,  21,  22,  23,  23,  24,  25,  25,  25,  26,  27,
                  27,  28,  29,  30,  30,  31,  31,  32,  32,  32,  34,  35,  37,  38,  40,  40,
                  40,  40,  40,  40,  40,  40,  40,  40,  40,  40,  40,  40,  40,  40,  41,  41,
                  42,  43,  43,  44,  45,  46,  47,  48,  49,  50,  51,  52,  53,  53,  54,  55,
                  56,  57,  58,  59,  60,  61,  62,  63,  63,  64,  65,  65,  66,  67,  68,  69,
                  70,  71,  72,  73,  74,  75,  76,  77,  78,  79,  80,  81,  81,  82,  83,  84,
                  85,  85,  87,  88,  89,  90,  91,  92,  92,  93,  94,  95,  96,  97,  98,  99,
                 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 113, 114,
                 115, 116, 117, 118, 119, 120, 121, 122, 123, 123, 125, 126, 127, 128, 129, 130,
                 131, 132, 133, 134, 135, 135, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146,
                 147, 148, 149, 149, 151, 152, 153, 154, 155, 156, 156, 158, 159, 160, 161, 162,
                 163, 164, 165, 166, 167, 168, 169, 170, 171, 172, 173, 174, 175, 176, 178, 179,
                 180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 191, 192, 193, 194, 195,
                 196, 197, 198, 199, 200, 201, 203, 203, 204, 205, 206, 207, 208, 210, 211, 212,
                 213, 214, 215, 216, 218, 218, 220, 221, 222, 223, 224, 225, 226, 232, 234, 255
                """),
    };
}
