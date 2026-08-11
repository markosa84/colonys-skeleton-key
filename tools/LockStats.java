package io.github.markosa84.colonysskeletonkey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import io.github.markosa84.colonysskeletonkey.solver.Connection;

/**
 * Regenerates {@code src/main/resources/known-locks.txt} from one or more solve histories, and
 * prints everything the corpus measures about how the game builds a lock. The numbers it prints are
 * the ones quoted in CLAUDE.md - never hand-edit those, come back here.
 *
 * <pre>
 *   gradlew classes
 *   javac -cp build\classes\java\main -d build\tools tools\LockStats.java
 *   java  -cp "build\classes\java\main;build\tools" io.github.markosa84.colonysskeletonkey.LockStats ^
 *         captures\lock-history.txt
 *   ... add --stats-only to measure a history without rewriting the bundled catalogue.
 * </pre>
 *
 * <p>It shares {@link LockCatalog}'s parser - one format, one reader - which is why it declares the
 * same package (see AGENTS.md, "Throwaway harnesses"; that package declaration is also why it is
 * compiled rather than run as a single-file source, which insists the path match). The catalogue it
 * writes drops the {@code keys}
 * line every history block carries: the recall path never replays recorded keys, it re-solves from
 * wherever the lock actually is, so those bytes would be dead weight in the jar.
 *
 * <h2>What the corpus is</h2>
 * The author's own solved chests, every one captured by an <b>untrained</b> character - which matters,
 * because Trained removes one plate connection and Master removes another. The bundled catalogue is
 * therefore the <i>maximal</i> model of each lock, and a maximal model is the one that cannot strain:
 * a move legal under a superset of the truth is legal under the truth.
 */
public final class LockStats {

    private static final Path DEFAULT_OUT = Path.of("src", "main", "resources", "known-locks.txt");

    public static void main(String[] args) throws IOException {
        List<Path> sources = new ArrayList<>();
        Path out = DEFAULT_OUT;
        boolean write = true;
        String assumeSkill = "";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--stats-only" -> write = false;
                case "-o" -> out = Path.of(args[++i]);
                // Histories written before LockHistory recorded the level carry none. Label them only
                // if you actually know it - the catalogue must not invent a fact about the character.
                case "--assume-skill" -> assumeSkill = args[++i].toLowerCase(Locale.ROOT);
                default -> sources.add(Path.of(args[i]));
            }
        }
        if (sources.isEmpty()) {
            sources.add(Path.of("captures", "lock-history.txt"));
        }

        List<LockCatalog.Entry> all = new ArrayList<>();
        for (Path source : sources) {
            List<LockCatalog.Entry> entries = LockCatalog.parse(Files.readAllLines(source));
            System.out.printf(Locale.ROOT, "%-40s %4d blocks%n", source, entries.size());
            all.addAll(entries);
        }

        List<LockCatalog.Entry> locks = distinct(all);
        System.out.printf(Locale.ROOT, "%n%d runs -> %d distinct locks%n", all.size(), locks.size());
        checkTheRecallKey(locks);
        report(locks);

        if (write) {
            Files.createDirectories(out.getParent());
            Files.writeString(out, catalogue(locks, assumeSkill), StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT, "%nwrote %s (%d locks, %d bytes)%n",
                    out, locks.size(), Files.size(out));
        }
    }

    /** One entry per distinct connection table, earliest first - the same chest solved twice is one. */
    private static List<LockCatalog.Entry> distinct(List<LockCatalog.Entry> all) {
        Map<String, LockCatalog.Entry> byModel = new LinkedHashMap<>();
        for (LockCatalog.Entry e : all) {
            byModel.putIfAbsent(e.n() + conn(e.connections()), e);
        }
        return List.copyOf(byModel.values());
    }

    /**
     * The invariant the whole recall path rests on: the offsets a lock shows at F8 identify its
     * connections. Over the first 124 real chests it held with not one collision - but it is a fact
     * about the game, not a theorem, and at 186 it no longer does: two four-plate chests both start
     * at {@code [3, 3, -3, -2]}. So a history that breaks it must say so loudly rather than quietly
     * hand the session an ambiguous model. {@code LockCatalog} acts on it too now, refusing a key it
     * knows two chests answer to; this check is what tells you it happened.
     */
    private static void checkTheRecallKey(List<LockCatalog.Entry> locks) {
        Map<String, LockCatalog.Entry> byState = new HashMap<>();
        int clashes = 0;
        for (LockCatalog.Entry e : locks) {
            LockCatalog.Entry had = byState.put(e.n() + Arrays.toString(e.state()), e);
            if (had != null) {
                clashes++;
                System.out.println("  COLLISION on " + Arrays.toString(e.state())
                        + ": " + conn(had.connections()) + "  vs  " + conn(e.connections()));
            }
        }
        System.out.println(clashes == 0
                ? "  (plate count, offsets) -> connections: a bijection, no collisions"
                : "  " + clashes + " state(s) map to more than one lock - recall would be ambiguous");
    }

    /** The bundled catalogue: one block per lock, in the format {@link LockCatalog} parses. */
    private static String catalogue(List<LockCatalog.Entry> locks, String assumeSkill) {
        StringBuilder sb = new StringBuilder("""
                # The Colony's Skeleton Key - locks this tool has already opened.
                #
                # Regenerate with tools/LockStats.java; see its javadoc. Same block format as
                # captures/lock-history.txt, minus the keys line, so entries can be copied across.
                #
                # The lockpicking level matters here: Trained removes one plate connection and Master
                # removes another, so a lock recorded UNTRAINED is the maximal model of its chest -
                # and a maximal model is the one recall cannot strain on, at any skill.

                """);
        int i = 0;
        for (LockCatalog.Entry e : locks) {
            String skill = e.skill().isEmpty() ? assumeSkill : e.skill();
            sb.append(String.format(Locale.ROOT, "lock %03d | %d plates%s%n", ++i, e.n(),
                    skill.isEmpty() ? "" : " | " + skill));
            sb.append("  init  ").append(Arrays.toString(e.state())).append(System.lineSeparator());
            sb.append("  conn  ").append(conn(e.connections())).append(System.lineSeparator());
            sb.append(System.lineSeparator());
        }
        return sb.toString();
    }

    /** {@code "1:3N,4I  4:0I"} - the same text {@code LockHistory} writes. */
    private static String conn(Connection[][] model) {
        StringBuilder sb = new StringBuilder();
        for (int p = 0; p < model.length; p++) {
            if (model[p].length == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("  ");
            }
            sb.append(p).append(':');
            for (int j = 0; j < model[p].length; j++) {
                sb.append(j > 0 ? "," : "").append(model[p][j].target())
                        .append(model[p][j].type() == Connection.Type.NORMAL ? 'N' : 'I');
            }
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }

    // --- what the corpus measures ---

    private static void report(List<LockCatalog.Entry> locks) {
        Map<Integer, Integer> outDegree = new TreeMap<>();
        Map<Integer, int[]> byDistance = new TreeMap<>();   // distance -> {edges, slots}
        Map<Integer, List<Integer>> edgesPerLock = new TreeMap<>();
        Map<Integer, double[]> byPosition = new TreeMap<>(); // n*10+p -> {edges, locks}
        Map<Integer, Integer> initOffsets = new TreeMap<>();
        Map<String, Integer> skills = new TreeMap<>();
        int edges = 0, slots = 0, normal = 0, reciprocal = 0, reciprocalSameType = 0;
        int plates = 0, dragsNothing = 0, draggedByNothing = 0, isolated = 0;

        for (LockCatalog.Entry lock : locks) {
            int n = lock.n();
            Connection[][] m = lock.connections();
            skills.merge(lock.skill().isEmpty() ? "(not recorded)" : lock.skill(), 1, Integer::sum);
            for (int v : lock.state()) {
                initOffsets.merge(v, 1, Integer::sum);
            }
            int[] inDegree = new int[n];
            for (Connection[] row : m) {
                for (Connection c : row) {
                    inDegree[c.target()]++;
                }
            }
            int perLock = 0;
            for (int p = 0; p < n; p++) {
                plates++;
                perLock += m[p].length;
                outDegree.merge(m[p].length, 1, Integer::sum);
                byPosition.computeIfAbsent(n * 10 + p, k -> new double[2])[0] += m[p].length;
                byPosition.get(n * 10 + p)[1]++;
                if (m[p].length == 0) {
                    dragsNothing++;
                }
                if (inDegree[p] == 0) {
                    draggedByNothing++;
                }
                if (m[p].length == 0 && inDegree[p] == 0) {
                    isolated++;
                }
                for (int q = 0; q < n; q++) {
                    if (q != p) {
                        slots++;
                        byDistance.computeIfAbsent(Math.abs(q - p), k -> new int[2])[1]++;
                    }
                }
                for (Connection c : m[p]) {
                    edges++;
                    byDistance.get(Math.abs(c.target() - p))[0]++;
                    if (c.type() == Connection.Type.NORMAL) {
                        normal++;
                    }
                    for (Connection back : m[c.target()]) {
                        if (back.target() == p) {
                            reciprocal++;
                            if (back.type() == c.type()) {
                                reciprocalSameType++;
                            }
                        }
                    }
                }
            }
            edgesPerLock.computeIfAbsent(n, k -> new ArrayList<>()).add(perLock);
        }

        System.out.println();
        System.out.println("skill recorded:      " + skills);
        percent("P(p drags q)", edges, slots);
        percent("  ... NORMAL", normal, edges);
        percent("  ... reciprocal", reciprocal, edges);
        percent("  ... same type when reciprocal", reciprocalSameType, reciprocal);
        System.out.println();

        System.out.println("P(edge) by |p-q|  (flat = no positional structure to exploit)");
        byDistance.forEach((d, e) -> percent("  distance " + d, e[0], e[1]));

        System.out.println();
        System.out.println("out-degree of a plate");
        int totalPlates = plates;
        outDegree.forEach((d, count) -> percent("  drags " + d, count, totalPlates));

        System.out.println();
        System.out.println("edges per lock  (the budget the risk prior is built on)");
        edgesPerLock.forEach((n, counts) -> {
            int[] histogram = new int[counts.stream().mapToInt(Integer::intValue).max().orElse(0) + 1];
            counts.forEach(c -> histogram[c]++);
            System.out.printf(Locale.ROOT, "  n=%d  locks=%3d  min=%2d max=%2d avg=%.2f  %s%n",
                    n, counts.size(), counts.stream().mapToInt(Integer::intValue).min().orElse(0),
                    histogram.length - 1,
                    counts.stream().mapToInt(Integer::intValue).average().orElse(0),
                    histogramText(histogram));
        });

        System.out.println();
        System.out.println("avg out-degree by plate position");
        byPosition.forEach((key, sum) -> System.out.printf(Locale.ROOT,
                "  n=%d plate %d  %.2f  (%d locks)%n",
                key / 10, key % 10, sum[0] / sum[1], (long) sum[1]));

        System.out.println();
        percent("plates that drag nothing", dragsNothing, plates);
        percent("plates nothing drags", draggedByNothing, plates);
        percent("fully isolated plates", isolated, plates);
        System.out.printf(Locale.ROOT, "  (independent in/out degree would predict %.1f isolated)%n",
                plates * (dragsNothing / (double) plates) * (draggedByNothing / (double) plates));

        System.out.println();
        System.out.println("offsets a lock starts at  (the generator favours the ends)");
        int totalOffsets = initOffsets.values().stream().mapToInt(Integer::intValue).sum();
        initOffsets.forEach((v, count) -> percent(String.format(Locale.ROOT, "  offset %+d", v),
                count, totalOffsets));
    }

    private static String histogramText(int[] histogram) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < histogram.length; i++) {
            if (histogram[i] > 0) {
                sb.append(i).append(':').append(histogram[i]).append(' ');
            }
        }
        return sb.toString().trim();
    }

    private static void percent(String label, int hits, int of) {
        System.out.printf(Locale.ROOT, "%-32s %5d / %5d = %.4f%n", label, hits, of,
                of == 0 ? 0.0 : hits / (double) of);
    }

    private LockStats() {}
}
