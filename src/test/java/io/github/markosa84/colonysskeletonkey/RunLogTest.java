package io.github.markosa84.colonysskeletonkey;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-F8 run log. Its whole reason to exist is that a bug report needs one self-contained file the
 * reporter can attach, not a console they must paste before it scrolls off - so what is tested is that
 * the file gets everything, the console gets the headline, and a stray press leaves nothing behind.
 */
class RunLogTest {

    @Test
    void teesTheHeadlineToBothAndTheVerboseHalfToTheFileAlone(@TempDir Path dir) throws Exception {
        Path logFile = dir.resolve("captures").resolve("f8.log"); // a directory it must create
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        RunLog log = RunLog.open(logFile, new PrintStream(console, true, StandardCharsets.UTF_8));

        assertTrue(log.hasFile());
        assertSame(logFile, log.path());
        log.out().println("headline");   // console + file
        log.out().write('!');            // exercises the single-byte path of the tee
        log.detail().println("verbose"); // file only
        log.keep();
        log.close();

        String file = Files.readString(logFile);
        assertTrue(file.contains("headline") && file.contains("verbose"), file);
        String seen = console.toString(StandardCharsets.UTF_8);
        assertTrue(seen.contains("headline") && seen.contains("!"), seen);
        assertFalse(seen.contains("verbose"), "the verbose trace is for the file, not the console");
    }

    @Test
    void anUnkeptLogIsDeletedSoAnIgnoredPressLeavesNothing(@TempDir Path dir) throws Exception {
        Path logFile = dir.resolve("f8.log");
        RunLog log = RunLog.open(logFile, new PrintStream(new ByteArrayOutputStream(), true,
                StandardCharsets.UTF_8));
        log.out().println("[F8] Ignored");

        log.close(); // never kept

        assertFalse(Files.exists(logFile), "an F8 the focus gate turned away must leave no log");
    }

    @Test
    void aFileThatCannotBeOpenedFallsBackToTheConsoleAlone(@TempDir Path dir) throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "x"); // a FILE where a dir is needed
        Path impossible = blocker.resolve("captures").resolve("f8.log");
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        PrintStream consoleStream = new PrintStream(console, true, StandardCharsets.UTF_8);

        RunLog log = RunLog.open(impossible, consoleStream);

        assertFalse(log.hasFile(), "no file could be opened");
        assertNull(log.detail(), "and so there is no verbose channel to write to");
        assertSame(consoleStream, log.out(), "logging carries on to the console alone");
        assertTrue(console.toString(StandardCharsets.UTF_8).contains("without a log file"),
                console.toString(StandardCharsets.UTF_8));
        log.close(); // a no-op when there is no file, and must not throw
    }
}
