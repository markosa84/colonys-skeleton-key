package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import javax.imageio.ImageIO;

/**
 * Writes frames to {@code captures/} so a live misread can be replayed offline against
 * {@link LockReader} - and, once understood, folded into {@code LockReaderCheck}'s fixtures.
 */
public final class Captures {

    /** Where frames are written, relative to the working directory. */
    private static final String DIR = "captures";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private Captures() {}

    /** Saves {@code img} as {@code captures/<tag>-<timestamp>.png} and prints where it went. */
    public static void save(BufferedImage img, String tag) {
        File dir = new File(DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.out.println("  (could not create " + dir.getAbsolutePath() + ")");
            return;
        }
        File out = new File(dir, tag + "-" + LocalDateTime.now().format(STAMP) + ".png");
        try {
            ImageIO.write(img, "png", out);
            System.out.println("  saved " + out.getAbsolutePath());
        } catch (IOException e) {
            System.out.println("  (could not save capture: " + e.getMessage() + ")");
        }
    }
}
