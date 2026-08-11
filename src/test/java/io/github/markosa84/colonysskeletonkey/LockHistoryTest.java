package io.github.markosa84.colonysskeletonkey;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markosa84.colonysskeletonkey.session.Skill;
import io.github.markosa84.colonysskeletonkey.solver.Connection;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cumulative history of solved locks. Unlike a failure dump, this is written on <b>success</b> and
 * <b>appended</b> to - so what is pinned is the exact concise shape of one entry, that a second solve
 * lands below the first, and that (like every diagnostic here) a file that will not open costs a line,
 * never the run the player already spent picks on.
 */
class LockHistoryTest {

    /** A fixed clock so the timestamp is predictable rather than "whenever the test ran". */
    private static final Clock AT = Clock.fixed(Instant.parse("2026-07-12T10:20:30Z"), ZoneOffset.UTC);

    @Test
    void appendsOneConciseEntryPerSolvedLock(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lock-history.txt");
        Connection[][] conn = {
            {}, {}, {new Connection(4, NORMAL), new Connection(3, INVERTED)}, {}, {}, {new Connection(1, NORMAL)}
        };

        Stdout.capturing(() -> new LockHistory(file, AT)
                .record(new int[] {3, -1, 2, 0, -3, 1}, conn, "SSSSSAADW", Optional.empty()));

        assertEquals(String.join("\n",
                "2026-07-12 10:20:30 | 6 plates",
                "  init  [3, -1, 2, 0, -3, 1]",
                "  conn  2:4N,3I  5:1N",
                "  keys  SSSSSAADW",
                "", ""), normalize(file));
    }

    /**
     * When a broken pick did reveal the level, it goes in the header - because the level changes the
     * lock, not just the pick: Trained removes one plate connection and Master removes another. A
     * model recorded untrained is the maximal one, and only a maximal model is safe to recall freely.
     */
    @Test
    void theLockpickingLevelIsRecordedWhenTheRunActuallySawIt(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lock-history.txt");

        Stdout.capturing(() -> new LockHistory(file, AT)
                .record(new int[] {0, 0, 0, 0}, new Connection[4][], "A", Optional.of(Skill.UNTRAINED)));

        assertTrue(normalize(file).startsWith("2026-07-12 10:20:30 | 4 plates | untrained"),
                normalize(file));
    }

    @Test
    void nothingDraggingReadsAsNone(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lock-history.txt");
        Connection[][] conn = {{}, {}, {}, {}};

        Stdout.capturing(() -> new LockHistory(file, AT)
                .record(new int[] {0, 0, 0, 0}, conn, "SSSS", Optional.empty()));

        assertTrue(normalize(file).contains("  conn  (none)"), normalize(file));
    }

    @Test
    void secondSolveIsAppendedBelowTheFirst(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lock-history.txt");
        LockHistory history = new LockHistory(file, AT);

        Stdout.capturing(() -> history.record(new int[] {1, -1, 1, -1}, new Connection[4][], "AADD", Optional.empty()));
        Stdout.capturing(() -> history.record(new int[] {2, 2, -1}, new Connection[3][], "WWSS", Optional.empty()));

        String got = normalize(file);
        assertTrue(got.indexOf("AADD") < got.indexOf("WWSS"), "the first entry must survive: " + got);
        assertEquals(2, got.split("plates", -1).length - 1, "two entries, both kept: " + got);
    }

    /** A path that cannot be written reports one line and returns - it never sinks the solve. */
    @Test
    void anUnwritableFileIsReportedRatherThanThrown(@TempDir Path dir) throws Exception {
        Path blocker = Files.createFile(dir.resolve("blocker")); // a FILE where a dir is needed
        Path impossible = blocker.resolve("captures").resolve("lock-history.txt");

        String log = Stdout.capturing(() -> new LockHistory(impossible, AT)
                .record(new int[] {0}, new Connection[1][], "", Optional.empty()));

        assertTrue(log.contains("could not append"), log);
    }

    /** A null connection row (unexpected at a solve, but survivable) prints {@code ?} rather than crashing. */
    @Test
    void anUnprobedRowIsMarkedNotCrashed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("lock-history.txt");
        Connection[][] conn = {null, {new Connection(0, NORMAL)}};

        Stdout.capturing(() -> new LockHistory(file, AT).record(new int[] {0, 0}, conn, "A", Optional.empty()));

        assertTrue(normalize(file).contains("  conn  0:?  1:0N"), normalize(file));
    }

    private static String normalize(Path file) throws Exception {
        return Files.readString(file).replace("\r\n", "\n");
    }
}
