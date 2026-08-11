package io.github.markosa84.colonysskeletonkey;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import io.github.markosa84.colonysskeletonkey.session.KnownLocks;
import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Every lock this tool has already opened, in memory, so it never has to learn the same chest twice.
 *
 * <p>Two sources, read once at startup and never again: the <b>bundled</b> {@code known-locks.txt}
 * that ships in the jar, then an overlay of {@code captures/lock-history.txt}, this machine's own
 * record of solved locks. Both are the exact block format {@link LockHistory} writes - one format,
 * one parser, and the shipped catalogue is literally a history file - so extending the bundle is
 * copying entries across.
 *
 * <p>From then on the catalogue is live: {@link #remember} folds a fresh solve straight into memory
 * beside {@link LockHistory}'s append, so reloading the save and re-opening the same chest is
 * recognised immediately, with no restart. {@code LockHistory} stays the only writer to disk.
 *
 * <h2>One key, two models</h2>
 * A key is {@code (plate count, offsets at F8)}, and it can be claimed twice. Which of the two things
 * that means is decided by the row algebra, not by a preference:
 *
 * <ul>
 *   <li><b>One model contains the other</b> - the same chest solved by an untrained character and
 *       again by a trained or master one, who sees one or two fewer plate connections. The
 *       <b>most connected</b> model wins, because that is the one that cannot strain: a move legal
 *       under a superset of the truth is legal under the truth (see {@link KnownLocks}).</li>
 *   <li><b>Neither contains the other</b> - and then no amount of training turns one into the other,
 *       so they are two different chests that happen to start at the same offsets. The key is marked
 *       {@link #ambiguousKeys() ambiguous} and {@link #recall} refuses it: a coin flip between two
 *       models is worse than probing, because a model of the wrong chest is neither superset nor
 *       subset and <i>can</i> strain the pick. It used to keep whichever had more connections, which
 *       silently poisoned the key for both chests forever.</li>
 * </ul>
 *
 * <p>{@link #matching} keeps both either way: it is keyed on the rows a run has actually probed,
 * which is a far stronger fingerprint than the offsets and tells the two chests apart at once.
 *
 * <p>Like every other diagnostic-adjacent file here, a source that will not read costs a line and
 * never the run.
 */
public final class LockCatalog implements KnownLocks {

    /** The catalogue that ships in the jar, seeded from the author's own solved locks. */
    public static final String BUNDLED = "/known-locks.txt";

    /** Rows are compared and stored target-sorted, so a file's ordering never matters. */
    private static final Comparator<Connection> BY_TARGET =
            Comparator.comparingInt(Connection::target);

    /** {@code key(n, state)} -> the connections remembered for it. */
    private final Map<Long, Connection[][]> byState = new HashMap<>();
    /** Plate count -> canonical model text -> the model, so one chest solved ten times is one entry. */
    private final Map<Integer, Map<String, Connection[][]>> byPlateCount = new HashMap<>();
    /** Keys two different chests both answer to. Never recalled - see {@link #remember}. */
    private final Set<Long> ambiguous = new HashSet<>();

    /** Production: the bundled catalogue, overlaid with everything this machine has solved. */
    public LockCatalog() {
        this(List.of(bundled(), sourceLines(Path.of("captures", "lock-history.txt"))));
    }

    /** Only these sources, for tests and for tools that want to inspect one history in isolation. */
    public static LockCatalog ofFiles(Path... files) {
        return new LockCatalog(Stream.of(files).map(LockCatalog::sourceLines).toList());
    }

    /**
     * Only what ships in the jar - no machine-local overlay. This is what the corpus tests run
     * against, so they measure the catalogue that is actually released rather than whatever the
     * developer's own {@code captures/} happens to hold that day.
     */
    public static LockCatalog bundledOnly() {
        return new LockCatalog(List.of(bundled()));
    }

    private LockCatalog(List<List<String>> sources) {
        for (List<String> lines : sources) {
            for (Entry e : parse(lines)) {
                remember(e.n(), e.state(), e.connections());
            }
        }
    }

    /** How many distinct locks are remembered - the one number worth putting in the banner. */
    public int size() {
        return byPlateCount.values().stream().mapToInt(Map::size).sum();
    }

    /**
     * How many starting configurations two different chests both answer to, and so cannot be
     * recognised by. Worth showing beside {@link #size()}: it only ever grows, and it is the number
     * that says how much of the catalogue the offsets can no longer name on their own.
     */
    public int ambiguousKeys() {
        return ambiguous.size();
    }

    // --- KnownLocks ---

    @Override
    public Optional<Connection[][]> recall(int n, int[] state) {
        if (state == null || state.length != n || !LockModel.isComplete(state)) {
            return Optional.empty();
        }
        // An ambiguous key is not in byState at all - remember() takes it out - so this is belt and
        // braces against a future path that puts one back.
        long key = key(n, state);
        return ambiguous.contains(key)
                ? Optional.empty()
                : Optional.ofNullable(byState.get(key)).map(LockCatalog::copy);
    }

    @Override
    public List<Connection[][]> matching(int n, Map<Integer, Connection[]> observedRows) {
        List<Connection[][]> out = new ArrayList<>();
        for (Connection[][] candidate : byPlateCount.getOrDefault(n, Map.of()).values()) {
            if (agrees(candidate, observedRows)) {
                out.add(copy(candidate));
            }
        }
        return out;
    }

    private static boolean agrees(Connection[][] candidate, Map<Integer, Connection[]> observed) {
        for (Map.Entry<Integer, Connection[]> row : observed.entrySet()) {
            int p = row.getKey();
            if (p < 0 || p >= candidate.length || !sameRow(candidate[p], row.getValue())) {
                return false;
            }
        }
        return true;
    }

    // --- learning ---

    /**
     * Folds one solved lock into memory. Called on every solve, so a chest opened a minute ago is
     * recognised the moment the player reloads and opens it again.
     *
     * <p>Keys accumulate per <i>observed</i> state rather than per chest: a run that started mid-lock
     * (an F8 retry) records that configuration as another key for the same connections, which is
     * simply another true fact about the same lock.
     *
     * <p>Two models under one key are two different things, and telling them apart is the whole of
     * {@link #ambiguous}: see the class javadoc.
     */
    public void remember(int n, int[] state, Connection[][] conn) {
        Connection[][] model = normalise(n, conn);
        if (model == null) {
            return;
        }
        byPlateCount.computeIfAbsent(n, k -> new LinkedHashMap<>())
                .putIfAbsent(canonical(model), model);
        if (state == null || state.length != n || !LockModel.isComplete(state)) {
            return; // a model worth remembering, but nothing to look it up by
        }
        long key = key(n, state);
        if (ambiguous.contains(key)) {
            return; // already known to name more than one chest; nothing can un-share it
        }
        Connection[][] had = byState.putIfAbsent(key, model);
        if (had == null || Arrays.deepEquals(had, model)) {
            return;
        }
        if (Connection.contains(had, model)) {
            return; // the same chest, seen with fewer connections: keep the maximal model
        }
        if (Connection.contains(model, had)) {
            byState.put(key, model); // the maximal one is the fresh one
            return;
        }
        // Neither contains the other, so no amount of lockpicking training turns one into the other:
        // these are two chests that happen to start at the same offsets. Refuse the key outright.
        ambiguous.add(key);
        byState.remove(key);
    }

    // --- the file format, shared with LockHistory ---

    /** One parsed block: the plate count, the offsets at F8, the connections, and the skill seen. */
    public record Entry(int n, int[] state, Connection[][] connections, String skill) {}

    /**
     * Every well-formed block in {@code lines}. Anything that does not parse - a truncated tail, a
     * hand-edit, an entry whose model was never fully learned ({@code 3:?}) - is skipped rather than
     * guessed at, because a wrong row here would be handed to the session as fact.
     */
    public static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<>();
        int n = -1;
        String skill = "";
        int[] state = null;
        String conn = null;
        for (String line : lines) {
            String header = headerPlates(line);
            if (header != null) {
                add(out, n, state, conn, skill);
                n = Integer.parseInt(header);
                skill = headerSkill(line);
                state = null;
                conn = null;
            } else if (line.stripLeading().startsWith("init")) {
                state = offsets(line);
            } else if (line.stripLeading().startsWith("conn")) {
                conn = line.stripLeading().substring("conn".length()).trim();
            }
        }
        add(out, n, state, conn, skill);
        return out;
    }

    private static void add(List<Entry> out, int n, int[] state, String conn, String skill) {
        if (n < LockModel.MIN_PLATES || n > LockModel.MAX_PLATES || state == null || conn == null
                || state.length != n) {
            return;
        }
        Connection[][] model = rows(n, conn);
        if (model != null) {
            out.add(new Entry(n, state, model, skill));
        }
    }

    /** The plate count off a {@code "... | 6 plates | untrained"} header, or null if not one. */
    private static String headerPlates(String line) {
        int bar = line.indexOf('|');
        if (bar < 0) {
            return null;
        }
        String[] parts = line.substring(bar + 1).split("\\|");
        String[] words = parts[0].trim().split("\\s+");
        if (words.length != 2 || !words[1].equals("plates")) {
            return null;
        }
        return words[0].chars().allMatch(Character::isDigit) && !words[0].isEmpty() ? words[0] : null;
    }

    /** The lockpicking level a header records, or {@code ""} when it predates the field. */
    private static String headerSkill(String line) {
        String[] parts = line.split("\\|");
        return parts.length < 3 ? "" : parts[2].trim().toLowerCase(Locale.ROOT);
    }

    /** {@code "  init  [1, 0, -1]"} -> the offsets, or null if the brackets do not hold numbers. */
    private static int[] offsets(String line) {
        int open = line.indexOf('['), close = line.lastIndexOf(']');
        if (open < 0 || close < open) {
            return null;
        }
        String body = line.substring(open + 1, close).trim();
        if (body.isEmpty()) {
            return null;
        }
        String[] parts = body.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return null;
            }
            if (Math.abs(out[i]) > LockModel.MAX_OFFSET) {
                return null;
            }
        }
        return out;
    }

    /**
     * {@code "1:3N,4I  4:0I"} -> a full connection table; plates the line does not mention drag
     * nothing. Null if anything about it is unusable, including the {@code ?} {@link LockHistory}
     * writes for a row that was never learned.
     */
    private static Connection[][] rows(int n, String text) {
        Connection[][] model = new Connection[n][];
        for (int p = 0; p < n; p++) {
            model[p] = new Connection[0];
        }
        if (text.equals("(none)")) {
            return model;
        }
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            int colon = token.indexOf(':');
            if (colon <= 0) {
                return null;
            }
            int p;
            try {
                p = Integer.parseInt(token.substring(0, colon));
            } catch (NumberFormatException e) {
                return null;
            }
            if (p < 0 || p >= n || model[p].length > 0) {
                return null; // out of range, or the same plate listed twice
            }
            List<Connection> row = new ArrayList<>();
            for (String drag : token.substring(colon + 1).split(",")) {
                Connection c = connection(n, p, drag, row);
                if (c == null) {
                    return null;
                }
                row.add(c);
            }
            model[p] = row.toArray(new Connection[0]);
        }
        return model;
    }

    /** {@code "4I"} -> the connection, or null if it is malformed, self-directed or a repeat. */
    private static Connection connection(int n, int p, String drag, List<Connection> row) {
        if (drag.length() < 2) {
            return null;
        }
        char kind = drag.charAt(drag.length() - 1);
        if (kind != 'N' && kind != 'I') {
            return null;
        }
        int target;
        try {
            target = Integer.parseInt(drag.substring(0, drag.length() - 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (target < 0 || target >= n || target == p) {
            return null;
        }
        for (Connection had : row) {
            if (had.target() == target) {
                return null;
            }
        }
        return new Connection(target,
                kind == 'N' ? Connection.Type.NORMAL : Connection.Type.INVERTED);
    }

    // --- reading the sources ---

    private static List<String> bundled() {
        try (InputStream in = LockCatalog.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                return List.of();
            }
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return r.lines().toList();
            }
        } catch (IOException | UncheckedIOException e) {
            System.out.println("  (could not read the bundled lock catalogue: " + e.getMessage() + ")");
            return List.of();
        }
    }

    private static List<String> sourceLines(Path file) {
        try {
            return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        } catch (IOException | UncheckedIOException e) {
            System.out.println("  (could not read " + file.toAbsolutePath() + ": " + e.getMessage() + ")");
            return List.of();
        }
    }

    // --- shared shapes ---

    /**
     * Mixed-radix key over the plate count and the offsets - lossless, so two chests collide here
     * exactly when they really do start in the same configuration. They can: 7^4 states for the
     * smallest lock, and the offsets are not uniform (each end of the track is about twice as likely
     * as any other position), so the effective space is smaller still. See the class javadoc.
     */
    private static long key(int n, int[] state) {
        long k = n;
        for (int v : state) {
            k = k * (2 * LockModel.MAX_OFFSET + 1) + (v + LockModel.MAX_OFFSET);
        }
        return k;
    }

    /**
     * A defensive, target-sorted copy, or null if the table is not a complete {@code n}-plate model.
     * Sorting is what lets a remembered row be compared with one the session learned: the session
     * builds rows in plate order, but nothing in the format guarantees a file does.
     */
    private static Connection[][] normalise(int n, Connection[][] conn) {
        if (conn == null || conn.length != n) {
            return null;
        }
        Connection[][] out = new Connection[n][];
        for (int p = 0; p < n; p++) {
            if (conn[p] == null) {
                return null; // an unprobed row is not a model
            }
            out[p] = conn[p].clone();
            Arrays.sort(out[p], BY_TARGET);
        }
        return out;
    }

    private static Connection[][] copy(Connection[][] model) {
        Connection[][] out = new Connection[model.length][];
        for (int p = 0; p < model.length; p++) {
            out[p] = model[p].clone();
        }
        return out;
    }

    /** True when two rows hold the same connections, whatever order each lists them in. */
    private static boolean sameRow(Connection[] a, Connection[] b) {
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        Connection[] x = a.clone(), y = b.clone();
        Arrays.sort(x, BY_TARGET);
        Arrays.sort(y, BY_TARGET);
        return Arrays.equals(x, y);
    }

    private static int edges(Connection[][] model) {
        int total = 0;
        for (Connection[] row : model) {
            total += row.length;
        }
        return total;
    }

    /** The model as one line, for de-duplicating chests solved more than once. */
    private static String canonical(Connection[][] model) {
        StringBuilder sb = new StringBuilder();
        for (Connection[] row : model) {
            sb.append('|');
            for (Connection c : row) {
                sb.append(c.target()).append(c.type() == Connection.Type.NORMAL ? 'N' : 'I').append(',');
            }
        }
        return sb.toString();
    }
}
