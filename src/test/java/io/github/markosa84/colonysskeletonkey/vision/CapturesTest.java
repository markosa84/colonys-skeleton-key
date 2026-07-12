package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markosa84.colonysskeletonkey.Stdout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failure-frame dumps. A capture is <b>evidence about</b> a problem, never the problem: if it
 * cannot be written the run must carry on regardless, because the alternative is losing the lock the
 * player already spent picks on to a full disk.
 */
class CapturesTest {

    /** A fixed clock, so the file name is predictable rather than "whenever the test ran". */
    private static final Clock AT = Clock.fixed(Instant.parse("2026-07-12T10:20:30.123Z"),
            ZoneOffset.UTC);
    private static final String STAMPED = "-20260712-102030-123.png";

    private final BufferedImage frame = new BufferedImage(16, 9, BufferedImage.TYPE_INT_RGB);

    @Test
    void savesATaggedTimestampedPng(@TempDir Path dir) throws IOException {
        Captures captures = new Captures(dir, AT);

        Path saved = Stdout.quietly(() -> captures.save(frame, "misread")).orElseThrow();

        assertEquals("misread" + STAMPED, saved.getFileName().toString());
        BufferedImage readBack = ImageIO.read(saved.toFile());
        assertEquals(16, readBack.getWidth(), "a real PNG, replayable against the reader offline");
        assertEquals(9, readBack.getHeight());
    }

    @Test
    void printsThePathSoTheFrameCanBeFound(@TempDir Path dir) {
        Captures captures = new Captures(dir, AT);

        String log = Stdout.capturing(() -> captures.save(frame, "no-lock"));

        assertTrue(log.contains("saved"), log);
        assertTrue(log.contains("no-lock" + STAMPED), log);
    }

    @Test
    void createsTheCapturesDirectoryOnFirstUse(@TempDir Path dir) {
        Path captureDir = dir.resolve("captures"); // does not exist yet
        Captures captures = new Captures(captureDir, AT);

        Optional<Path> saved = Stdout.quietly(() -> captures.save(frame, "first"));

        assertTrue(saved.isPresent());
        assertTrue(Files.isDirectory(captureDir));
        assertTrue(Files.exists(captureDir.resolve("first" + STAMPED)));
    }

    /** A directory that cannot be created costs the dump, not the run. */
    @Test
    void anImpossibleDirectoryIsReportedRatherThanThrown(@TempDir Path dir) throws IOException {
        Path blocked = Files.createFile(dir.resolve("not-a-directory"));
        Captures captures = new Captures(blocked.resolve("captures"), AT);

        String log = Stdout.capturing(() ->
                assertTrue(captures.save(frame, "misread").isEmpty(), "no file, no exception"));

        assertTrue(log.contains("could not create"), log);
    }

    /** Nor does an unwritable target: the frame is lost, the session survives. */
    @Test
    void anUnwritableFileIsReportedRatherThanThrown(@TempDir Path dir) throws IOException {
        // Occupy the exact name the fixed clock will produce with a non-empty directory: ImageIO
        // deletes whatever stands in its way first, and a directory with something in it survives
        // that, so the write itself fails.
        Path blocking = Files.createDirectory(dir.resolve("misread" + STAMPED));
        Files.createFile(blocking.resolve("keep-me"));
        Captures captures = new Captures(dir, AT);

        String log = Stdout.capturing(() ->
                assertTrue(captures.save(frame, "misread").isEmpty(), "no file, no exception"));

        assertTrue(log.contains("could not save capture"), log);
    }
}
