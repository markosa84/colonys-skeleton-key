package io.github.markosa84.colonysskeletonkey.vision;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Loads the labelled frames under {@code src/test/data/frames/}.
 *
 * <p>The directory is deliberately not a classpath resource - Gradle would copy the whole corpus
 * into {@code build/resources/test} on every clean build. The Gradle {@code test} task passes the
 * absolute path as {@code -Dlockpick.frames.dir} (and declares the directory a task input, so a
 * re-labelled frame re-runs the gate); the relative default covers any runner started from the
 * repository root.
 *
 * <p>The frames are <b>shrunk</b> ({@code scripts/shrink-frames.ps1}): full screen dimensions, but
 * black outside the lock box and the lockpick counter, which is all the reader ever samples. Pixels
 * inside those boxes are byte-identical to the original capture, so nothing here is a
 * reconstruction - a frame simply arrives looking like what {@link GameScreen#captureLock()} hands
 * the reader live.
 *
 * <p>The frames are part of the repository, so every checkout can re-run the reader's calibration -
 * and a checkout that cannot is broken. {@link #available()} exists only to say so with a decent
 * message: the frame-driven classes assert it rather than skipping, because a skip would switch the
 * entire calibration off and still report a green suite.
 *
 * <p>This class is the one place a frame is read. Anything new that needs a frame goes through it.
 */
public final class TestFrames {

    private static final File DIR =
            new File(System.getProperty("lockpick.frames.dir", "src/test/data/frames"));

    private TestFrames() {}

    /** True if the labelled frames are on this machine at all; false means a broken checkout. */
    public static boolean available() {
        return DIR.isDirectory();
    }

    /** Reads {@code name} (relative to the frames directory) as an image, or fails loudly. */
    public static BufferedImage load(String name) {
        try {
            return ImageIO.read(path(name).toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not decode " + name, e);
        }
    }

    /** The frame's path, for the few things that take a file rather than an image (--diagnose). */
    public static Path path(String name) {
        File file = new File(DIR, name);
        if (!file.isFile()) {
            throw new IllegalStateException("Missing test frame " + file.getAbsolutePath()
                    + " - run via Gradle, or start the JVM in the repository root, or point"
                    + " -Dlockpick.frames.dir at src/test/data/frames.");
        }
        return file.toPath();
    }
}
