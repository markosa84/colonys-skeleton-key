package io.github.markosa84.colonysskeletonkey.session;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.markosa84.colonysskeletonkey.LockCatalog;
import io.github.markosa84.colonysskeletonkey.Stdout;
import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.Cost;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.LockSolver;
import io.github.markosa84.colonysskeletonkey.solver.ModelRepair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole algorithm, driven against every real lock this tool has ever opened.
 *
 * <p>Everything else in the suite is a unit test over a lock invented to exercise one branch. This is
 * the opposite: the bundled catalogue is every chest the game actually produced for the author, with
 * the connection tables it actually gave them, and {@link FakeGame} replays the game's real strain,
 * break and reset rules over them. It is to the session what the frame corpus is to the reader - the
 * thing that turns "this change feels better" into a number. It grows as locks are opened, so its
 * totals are comparable only against a run over the <i>same</i> catalogue.
 *
 * <p><b>It prints its totals.</b> Strains, plays and give-ups per plate count and per lockpicking
 * level are the baseline any future change to discovery argues against; the assertions are a floor
 * under that, not the point of the test.
 *
 * <p>Two sizes. {@code gradlew test} runs a fixed sample so the suite stays quick; {@code gradlew
 * corpus} sets {@code -Dlockpick.corpus=full} and sweeps every lock at all three levels. The
 * sample is every {@value #SAMPLE_STRIDE}th lock - deterministic, and spread across plate counts -
 * plus the locks that historically cost the most strains ({@link #HARD}), and it runs untrained only,
 * that being the level with the most connections and the only break that resets the puzzle.
 */
class LockCorpusTest {

    /** Every Nth catalogue lock lands in the quick sample. */
    private static final int SAMPLE_STRIDE = 25;

    /**
     * The locks the last full sweep spent the most strains on - between them, about a third of the
     * whole corpus's. Kept in the quick sample so a regression in the awkward cases cannot hide until
     * someone remembers to run {@code gradlew corpus}. Identified by their offsets at F8, which is the
     * catalogue's own key.
     *
     * <p>Re-chosen from evidence, not memory: the full sweep prints its own costliest list, and three
     * of the five locks that used to sit here are no longer expensive at all. Refresh it whenever
     * discovery changes shape.
     */
    private static final List<String> HARD = List.of(
            "[2, -3, -3, 2, -3]",
            "[2, -3, 3, 0, 3, -2]",
            "[-2, -2, -3, 3, 3]",
            "[-2, 3, 3, 3, 3]",
            "[3, 0, 2, 1, 3, -3]",
            "[-1, -3, 3, -1, -3]",
            "[0, -2, 3, -3]",
            "[-3, -3, -2, -2, 2, 1]");

    private static List<LockCatalog.Entry> corpus;

    @BeforeAll
    static void loadTheCorpus() throws Exception {
        try (var in = LockCatalog.class.getResourceAsStream(LockCatalog.BUNDLED)) {
            assertTrue(in != null, "known-locks.txt must ship in the jar");
            corpus = LockCatalog.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
        assertTrue(corpus.size() >= 100, "the corpus should be the whole catalogue: " + corpus.size());
    }

    /** True under {@code gradlew corpus}: sweep everything instead of the quick sample. */
    private static boolean full() {
        return "full".equals(System.getProperty("lockpick.corpus"));
    }

    /**
     * The levels this run covers. A session is around a second, so the quick sample sticks to
     * untrained - the level with the most connections, the most strains and the only break that
     * resets the puzzle, and therefore the one worth having in every build.
     */
    private static List<Skill> levels() {
        return full() ? List.of(Skill.values()) : List.of(Skill.UNTRAINED);
    }

    /** The locks this run covers: everything under {@code corpus}, a deterministic sample otherwise. */
    private static List<LockCatalog.Entry> underTest() {
        if (full()) {
            return corpus;
        }
        List<LockCatalog.Entry> sample = new ArrayList<>();
        for (int i = 0; i < corpus.size(); i++) {
            if (i % SAMPLE_STRIDE == 0 || HARD.contains(Arrays.toString(corpus.get(i).state()))) {
                sample.add(corpus.get(i));
            }
        }
        return sample;
    }

    /**
     * Every real lock opens, from the state it was really in, at every lockpicking level - learning
     * the connections from scratch, exactly as a first encounter does.
     */
    @Test
    void everyRealLockOpensFromScratchAtEveryLevel() {
        Totals totals = new Totals();
        List<String> failures = new ArrayList<>();
        Map<String, Integer> strainsPerLock = new HashMap<>();
        for (LockCatalog.Entry lock : underTest()) {
            for (Skill skill : levels()) {
                LockModel truth = truth(lock, skill);
                if (truth == null) {
                    continue; // no removal leaves this one openable; the game would not ship it
                }
                FakeGame game = new FakeGame(truth, skill);
                Stdout.capturing(() -> new LockSession(game, game, game).run());
                totals.add(lock.n(), skill, game);
                strainsPerLock.merge(Arrays.toString(lock.state()), game.strains, Integer::sum);
                if (!game.opened()) {
                    failures.add(Arrays.toString(lock.state()) + " at " + skill
                            + " (strains " + game.strains + ", breaks " + game.breaks
                            + ", dumps " + game.dumps + ")");
                }
            }
        }
        System.out.println(totals.report("discovery, from scratch"));
        // Named so the quick sample's HARD list can be chosen from evidence rather than memory.
        System.out.println("  costliest locks (strains, summed over the levels run):");
        strainsPerLock.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .forEach(e -> System.out.printf(Locale.ROOT, "    %-28s %d%n", e.getKey(), e.getValue()));
        assertEquals(List.of(), failures, "locks the game hands out always open");
    }

    /**
     * The same corpus, recognised. This is the measurement the catalogue exists for: what recall
     * saves in moves, and that it spends no strains doing it.
     *
     * <p>The saving is real but uneven - on some chests every discovery move already lay on the
     * solution path, so recall costs the same. What it never does is cost more, and it never gambles.
     */
    @Test
    void recallOpensEveryRealLockWithoutProbingAndWithoutStraining() {
        int recallPlays = 0, probePlays = 0, recallStrains = 0, probeStrains = 0, saved = 0;
        int shared = 0;
        List<String> failures = new ArrayList<>();
        List<LockCatalog.Entry> locks = underTest();
        LockCatalog known = LockCatalog.bundledOnly(); // read once, as the running app reads it
        for (LockCatalog.Entry lock : locks) {
            LockModel truth = truth(lock, Skill.UNTRAINED);

            FakeGame recalling = new FakeGame(truth, Skill.UNTRAINED);
            Stdout.capturing(() ->
                    new LockSession(recalling, recalling, recalling, known).run());
            FakeGame probing = new FakeGame(truth, Skill.UNTRAINED);
            Stdout.capturing(() -> new LockSession(probing, probing, probing).run());

            recallPlays += recalling.plays;
            probePlays += probing.plays;
            recallStrains += recalling.strains;
            probeStrains += probing.strains;
            if (recalling.plays < probing.plays) {
                saved++;
            }
            // A chest whose offsets another chest also shows is deliberately NOT recalled - the
            // catalogue refuses the key rather than hand over a coin flip - so it is learned the
            // ordinary way and costs exactly what probing costs. It must still open.
            if (known.recall(lock.n(), lock.state()).isEmpty()) {
                shared++;
                if (!recalling.opened()) {
                    failures.add(Arrays.toString(lock.state())
                            + ": shares its key with another chest and did not open by probing");
                }
                continue;
            }
            if (!recalling.opened() || recalling.strains > 0 || recalling.plays > probing.plays) {
                failures.add(Arrays.toString(lock.state()) + ": opened=" + recalling.opened()
                        + " plays=" + recalling.plays + " (probing " + probing.plays + ")"
                        + " strains=" + recalling.strains);
            }
            // A recognised lock is solved outright, so the run is exactly the shortest solution.
            int solution = LockSolver.solve(truth, lock.state(), lock.n() - 1, Cost.WALLCLOCK).size();
            if (recalling.plays != solution) {
                failures.add(Arrays.toString(lock.state()) + ": " + recalling.plays
                        + " plays for a " + solution + "-move solution - something was probed");
            }
        }
        System.out.printf(Locale.ROOT,
                "%nrecall vs discovery over %d locks:%n"
                + "  plays   %5d recalled  vs %5d probed  (%.1f%% fewer, %d locks strictly cheaper)%n"
                + "  strains %5d recalled  vs %5d probed%n"
                + "  %d lock(s) share their offsets with another chest and are probed, not recalled%n",
                locks.size(), recallPlays, probePlays,
                100.0 * (probePlays - recallPlays) / probePlays, saved, recallStrains, probeStrains,
                shared);
        assertEquals(List.of(), failures, "recall must be free and exact");
    }

    /**
     * The safety property recall rests on, checked against every real lock rather than argued: a
     * character who has trained since the catalogue was recorded meets a lock with one or two fewer
     * connections than the memory holds, and the over-full memory still cannot strain the pick.
     */
    @Test
    void recallNeverStrainsEvenWhenTheCharacterHasTrainedSinceTheLockWasRecorded() {
        List<String> failures = new ArrayList<>();
        KnownLocks untrainedMemory = LockCatalog.bundledOnly();
        for (LockCatalog.Entry lock : underTest()) {
            for (Skill skill : full() ? List.of(Skill.BASIC, Skill.MASTER) : List.of(Skill.BASIC)) {
                LockModel truth = truth(lock, skill);
                if (truth == null) {
                    continue; // no removal leaves this lock openable; the game would not ship it
                }
                FakeGame game = new FakeGame(truth, skill);
                Stdout.capturing(() ->
                        new LockSession(game, game, game, untrainedMemory).run());
                if (!game.opened() || game.strains > 0) {
                    failures.add(Arrays.toString(lock.state()) + " at " + skill + ": opened="
                            + game.opened() + " strains=" + game.strains);
                }
            }
        }
        assertEquals(List.of(), failures,
                "a memory that is a superset of the truth can mispredict, never strain");
    }

    // --- building the lock a given character would meet ---

    /**
     * The lock as {@code skill} sees it: the catalogue's own model untrained, one connection lighter
     * trained, two lighter at master. Null when no such removal leaves the lock openable - the game
     * only ever hands out solvable puzzles, so that is a lock this character would simply never meet.
     *
     * <p>Memoised, because building it is the expensive half of this test - three tests ask for the
     * same models, and each removal costs a search over the whole configuration space.
     */
    private static LockModel truth(LockCatalog.Entry lock, Skill skill) {
        return TRUTHS.computeIfAbsent(Arrays.toString(lock.state()) + skill,
                k -> Optional.ofNullable(build(lock, skill))).orElse(null);
    }

    private static final Map<String, Optional<LockModel>> TRUTHS = new HashMap<>();

    private static LockModel build(LockCatalog.Entry lock, Skill skill) {
        Connection[][] model = lock.connections();
        int removals = switch (skill) {
            case UNTRAINED -> 0;
            case BASIC -> 1;
            case MASTER -> 2;
        };
        for (int r = 0; r < removals; r++) {
            model = withOneConnectionRemoved(model, lock.state());
            if (model == null) {
                return null;
            }
        }
        return LockModel.of(lock.state(), model);
    }

    /**
     * One connection gone, choosing one whose removal leaves the lock openable. Null if none does.
     *
     * <p>Openability is asked with {@link ModelRepair#reachesGoal} - a plain reachability flood - and
     * not with {@link LockSolver#solve}: the question is whether the goal is reachable at all, not by
     * what path, and the flood is several times cheaper over a search that runs up to ten times per
     * removal.
     */
    private static Connection[][] withOneConnectionRemoved(Connection[][] model, int[] start) {
        for (int p = 0; p < model.length; p++) {
            for (int k = 0; k < model[p].length; k++) {
                Connection[][] fewer = model.clone();
                List<Connection> kept = new ArrayList<>(List.of(model[p]));
                kept.remove(k);
                fewer[p] = kept.toArray(new Connection[0]);
                if (ModelRepair.reachesGoal(LockModel.of(start, fewer), start)) {
                    return fewer;
                }
            }
        }
        return null;
    }

    // --- the numbers ---

    /** Per plate count and per level: what the corpus cost. */
    private static final class Totals {

        private final int[][] plays = new int[8][3];
        private final int[][] strains = new int[8][3];
        private final int[][] breaks = new int[8][3];
        private final int[][] locks = new int[8][3];

        void add(int n, Skill skill, FakeGame game) {
            int s = skill.ordinal();
            plays[n][s] += game.plays;
            strains[n][s] += game.strains;
            breaks[n][s] += game.breaks;
            locks[n][s]++;
        }

        String report(String title) {
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                    "%n=== corpus: %s ===%n  %-10s %6s %8s %9s %8s%n",
                    title, "plates", "locks", "plays", "strains", "breaks"));
            int totalLocks = 0, totalPlays = 0, totalStrains = 0, totalBreaks = 0;
            for (int n = LockModel.MIN_PLATES; n <= LockModel.MAX_PLATES; n++) {
                for (Skill skill : Skill.values()) {
                    int s = skill.ordinal();
                    if (locks[n][s] == 0) {
                        continue;
                    }
                    sb.append(String.format(Locale.ROOT, "  %d, %-7s %6d %8d %9d %8d%n",
                            n, skill.name().toLowerCase(Locale.ROOT), locks[n][s], plays[n][s],
                            strains[n][s], breaks[n][s]));
                    totalLocks += locks[n][s];
                    totalPlays += plays[n][s];
                    totalStrains += strains[n][s];
                    totalBreaks += breaks[n][s];
                }
            }
            sb.append(String.format(Locale.ROOT, "  %-10s %6d %8d %9d %8d%n",
                    "TOTAL", totalLocks, totalPlays, totalStrains, totalBreaks));
            return sb.toString();
        }
    }
}
