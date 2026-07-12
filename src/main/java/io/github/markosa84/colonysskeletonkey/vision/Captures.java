package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import javax.imageio.ImageIO;

/**
 * Writes frames to {@code captures/} so a live misread can be replayed offline against
 * {@link LockReader} - and, once understood, folded into {@code LockReaderTest}'s fixtures.
 *
 * <p>The directory and the clock are injected rather than hardcoded, so a test can write into a
 * temporary directory and predict the file name it gets. Production uses the no-arg constructor:
 * {@code captures/} beside the working directory, on the wall clock.
 */
public final class Captures {

    /** Where frames are written, relative to the working directory. */
    private static final String DIR = "captures";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private final Path dir;
    private final Clock clock;

    public Captures() {
        this(Path.of(DIR), Clock.systemDefaultZone());
    }

    Captures(Path dir, Clock clock) {
        this.dir = dir;
        this.clock = clock;
    }

    /**
     * Saves {@code img} as {@code <dir>/<tag>-<timestamp>.png} and prints where it went, returning
     * the file written - or empty if it could not be, which is never worth failing a run over: the
     * capture is evidence about a problem, not the problem.
     */
    public Optional<Path> save(BufferedImage img, String tag) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.out.println("  (could not create " + dir.toAbsolutePath() + ")");
            return Optional.empty();
        }
        Path out = dir.resolve(tag + "-" + LocalDateTime.now(clock).format(STAMP) + ".png");
        try {
            ImageIO.write(img, "png", out.toFile());
            System.out.println("  saved " + out.toAbsolutePath());
            return Optional.of(out);
        } catch (IOException e) {
            System.out.println("  (could not save capture: " + e.getMessage() + ")");
            return Optional.empty();
        }
    }
}
