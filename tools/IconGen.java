import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Draws the application icon - a small brass skeleton key - and writes it as a Windows .ico.
 *
 * <p>The art is ours, drawn from primitives, which is the point: the tool ships no pixel that came
 * out of the game. Run it once and commit the result; it is deterministic.
 *
 * <pre>{@code
 *   java tools/IconGen.java packaging/ColonysSkeletonKey.ico
 * }</pre>
 *
 * <p>The ICO container is simpler than its reputation: a 6-byte header, one 16-byte directory entry
 * per image, then the payloads. Since Vista an entry's payload may be a whole PNG file rather than a
 * DIB, so {@link ImageIO} does all the encoding and this class only writes the two headers.
 */
public final class IconGen {

    /** Windows picks the size it wants from these; 256 is what modern shells actually show. */
    private static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private static final Color BRASS_LIGHT = new Color(0xE8, 0xC4, 0x6A);
    private static final Color BRASS = new Color(0xC9, 0x9A, 0x3B);
    private static final Color BRASS_DARK = new Color(0x8A, 0x63, 0x1E);

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args.length > 0 ? args[0] : "packaging/ColonysSkeletonKey.ico");
        Files.createDirectories(out.toAbsolutePath().getParent());

        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(draw(size), "png", png);
            pngs.add(png.toByteArray());
        }
        try (OutputStream file = Files.newOutputStream(out)) {
            writeIco(file, pngs);
        }
        System.out.println("Wrote " + out.toAbsolutePath() + " (" + Files.size(out) + " bytes, "
                + SIZES.length + " sizes)");
    }

    /** The key itself: a ring bow, a shaft, two teeth. Drawn on a unit square, scaled to {@code s}. */
    private static BufferedImage draw(int s) {
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(s / 100.0, s / 100.0); // draw in a 100x100 space, whatever the real size

        // Turned 45 degrees so the key fills the square rather than a thin diagonal band.
        g.rotate(Math.toRadians(-45), 50, 50);

        // The shaft, running from under the bow down to the teeth.
        g.setColor(BRASS);
        g.fill(new RoundRectangle2D.Double(45, 30, 10, 52, 6, 6));

        // The bow: a thick ring at the top.
        g.setStroke(new BasicStroke(11f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(BRASS);
        g.draw(new Ellipse2D.Double(31, 8, 38, 38));

        // Its hole, punched back out so the ring reads as a ring at 16px too.
        g.setComposite(java.awt.AlphaComposite.Clear);
        g.fill(new Ellipse2D.Double(39, 16, 22, 22));
        g.setComposite(java.awt.AlphaComposite.SrcOver);

        // Two teeth on one side of the shaft's foot - the silhouette that says "key".
        g.setColor(BRASS);
        g.fill(new RoundRectangle2D.Double(55, 58, 18, 9, 3, 3));
        g.fill(new RoundRectangle2D.Double(55, 74, 13, 9, 3, 3));

        // A highlight down one edge and a shadow down the other: enough to look forged, not flat.
        // Both stay strictly inside the shaft - run them any longer and they cut across the bow's
        // hole, which at 16px reads as a smudge rather than a key.
        g.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(BRASS_LIGHT);
        g.drawLine(48, 46, 48, 78);
        g.setColor(BRASS_DARK);
        g.drawLine(53, 46, 53, 78);

        g.dispose();
        return img;
    }

    /**
     * Writes the ICO wrapper: {@code ICONDIR} (reserved, type=1, count), then one
     * {@code ICONDIRENTRY} per image (width, height, 0 colours, 0 reserved, 1 plane, 32bpp, byte
     * size, byte offset), then the PNG payloads themselves.
     */
    private static void writeIco(OutputStream out, List<byte[]> pngs) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        le16(d, 0);                 // reserved
        le16(d, 1);                 // type 1 = icon
        le16(d, pngs.size());

        int offset = 6 + 16 * pngs.size();
        for (int i = 0; i < pngs.size(); i++) {
            int size = SIZES[i];
            d.writeByte(size >= 256 ? 0 : size); // 0 means 256
            d.writeByte(size >= 256 ? 0 : size);
            d.writeByte(0);         // palette size: none, it is truecolour
            d.writeByte(0);         // reserved
            le16(d, 1);             // colour planes
            le16(d, 32);            // bits per pixel
            le32(d, pngs.get(i).length);
            le32(d, offset);
            offset += pngs.get(i).length;
        }
        for (byte[] png : pngs) {
            d.write(png);
        }
        d.flush();
    }

    private static void le16(DataOutputStream d, int v) throws IOException {
        d.writeByte(v & 0xFF);
        d.writeByte((v >> 8) & 0xFF);
    }

    private static void le32(DataOutputStream d, int v) throws IOException {
        le16(d, v & 0xFFFF);
        le16(d, (v >>> 16) & 0xFFFF);
    }

    private IconGen() {}
}
