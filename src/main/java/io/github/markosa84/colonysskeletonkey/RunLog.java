package io.github.markosa84.colonysskeletonkey;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The per-F8 run log: everything a solve prints, saved to a file so a bug report is one self-contained
 * artifact rather than a console the player has to scroll back through and paste - before the useful
 * lines have scrolled off, as they did in the reports these logs exist to answer.
 *
 * <p>It works by teeing: {@link #out()} writes to both the console and the file, and the caller installs
 * it as {@link System#out} for the duration of the run (that one line is the only part that has to live
 * in the untestable composition root). {@link #detail()} is a file-only channel for the verbose half of
 * the report - the full environment, the reader's account of the frame, the move-by-move trace - which
 * would drown the console but is exactly what debugging needs.
 *
 * <p>If the file cannot be opened the solve still runs, logging to the console alone: a diagnostic is
 * never worth failing a run over. And a log is {@link #keep() kept} only if the run actually happened;
 * an F8 the focus gate turned away leaves nothing behind.
 */
final class RunLog implements AutoCloseable {

    private final Path path;      // null when no file could be opened
    private final PrintStream file; // the file, or null; also the detail() channel
    private final PrintStream out;   // console + file, or just console
    private boolean kept;

    private RunLog(Path path, PrintStream file, PrintStream out) {
        this.path = path;
        this.file = file;
        this.out = out;
    }

    /**
     * Opens a run log that tees {@code console} into {@code path}. On any I/O failure - the directory
     * cannot be made, the file cannot be created - it falls back to a console-only log (no file) and
     * says so on {@code console}, rather than failing.
     */
    static RunLog open(Path path, PrintStream console) {
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            PrintStream file = new PrintStream(new FileOutputStream(path.toFile()), true,
                    StandardCharsets.UTF_8);
            PrintStream tee = new PrintStream(new Tee(console, file), true, StandardCharsets.UTF_8);
            return new RunLog(path, file, tee);
        } catch (IOException e) {
            console.println("  (running without a log file: " + e.getMessage() + ")");
            return new RunLog(null, null, console);
        }
    }

    /** Install this as {@link System#out} for the run: it reaches both the console and the file. */
    PrintStream out() {
        return out;
    }

    /** The file-only verbose channel, or null when there is no file - callers must null-check. */
    PrintStream detail() {
        return file;
    }

    boolean hasFile() {
        return file != null;
    }

    Path path() {
        return path;
    }

    /** Mark the run as worth keeping; an unkept log is deleted on {@link #close()}. */
    void keep() {
        kept = true;
    }

    @Override
    public void close() {
        if (file == null) {
            return;
        }
        file.close();
        if (!kept) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // a stray empty log is not worth a word
            }
        }
    }

    /** Writes to two streams at once - the console and the run-log file - closing neither. */
    private static final class Tee extends OutputStream {
        private final OutputStream a;
        private final OutputStream b;

        Tee(OutputStream a, OutputStream b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void write(int x) throws IOException {
            a.write(x);
            b.write(x);
        }

        @Override
        public void write(byte[] buf, int off, int len) throws IOException {
            a.write(buf, off, len);
            b.write(buf, off, len);
        }

        @Override
        public void flush() throws IOException {
            a.flush();
            b.flush();
        }
    }
}
