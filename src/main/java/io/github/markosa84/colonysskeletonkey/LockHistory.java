package io.github.markosa84.colonysskeletonkey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;
import java.util.StringJoiner;

import io.github.markosa84.colonysskeletonkey.solver.Connection;

/**
 * Appends one concise line-block per <b>solved</b> lock to {@code captures/lock-history.txt}: when it
 * was opened, the state it was in when F8 was pressed, the connections the tool learned, and the whole
 * literal {@code W/S/A/D} sequence it pressed to open it. Unlike the per-F8 {@code RunLog}, this is a
 * cumulative record of successes - it is never written on a failure, and never truncated.
 *
 * <p>The file and the clock are injected, mirroring {@link io.github.markosa84.colonysskeletonkey.vision.Captures}:
 * a test writes into a temporary file with a fixed clock and predicts the text exactly. Production uses
 * the no-arg constructor. Like every diagnostic here, a file that will not open costs a line, never the
 * run.
 */
final class LockHistory {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final Path file;
    private final Clock clock;

    LockHistory() {
        this(Path.of("captures", "lock-history.txt"), Clock.systemDefaultZone());
    }

    LockHistory(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
    }

    /** Appends the record for one solved lock. Prints where it went; never throws. */
    void record(int[] initialState, Connection[][] conn, String keys) {
        String entry = format(initialState, conn, keys);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.out.println("  (could not append " + file.toAbsolutePath() + ": " + e.getMessage() + ")");
        }
    }

    private String format(int[] initialState, Connection[][] conn, String keys) {
        return String.format(Locale.ROOT,
                "%s | %d plates%n  init  %s%n  conn  %s%n  keys  %s%n%n",
                LocalDateTime.now(clock).format(STAMP), initialState.length,
                Arrays.toString(initialState), describeConnections(conn), keys);
    }

    /** {@code "2:4N,3I  5:1N"} - only plates that drag something; {@code (none)} if nothing does. */
    private static String describeConnections(Connection[][] conn) {
        StringJoiner plates = new StringJoiner("  ");
        for (int p = 0; p < conn.length; p++) {
            if (conn[p] == null) {
                plates.add(p + ":?"); // not expected at a solve, but honest if it happens
            } else if (conn[p].length > 0) {
                StringJoiner drags = new StringJoiner(",");
                for (Connection c : conn[p]) {
                    drags.add(c.target() + (c.type() == Connection.Type.NORMAL ? "N" : "I"));
                }
                plates.add(p + ":" + drags);
            }
        }
        String s = plates.toString();
        return s.isEmpty() ? "(none)" : s;
    }
}
