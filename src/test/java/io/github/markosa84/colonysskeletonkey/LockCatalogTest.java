package io.github.markosa84.colonysskeletonkey;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;

import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.INVERTED;
import static io.github.markosa84.colonysskeletonkey.solver.Connection.Type.NORMAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The memory of locks already opened. Two things are pinned here and they pull in opposite
 * directions: a hit has to be <b>exact</b> (a wrong row handed to the session as fact is the one
 * mistake this whole codebase is built to avoid), and it has to arrive <b>immediately</b> - the point
 * of {@link LockCatalog#remember} is that reloading a save and re-opening the same chest is
 * recognised without restarting the tool.
 *
 * <p>The bundled catalogue is checked as a shipped artifact, not just as parseable text: every entry
 * must recall itself, <i>unless</i> another chest in the file shows the same offsets at F8 - the key
 * is a measurement rather than a theorem, and the catalogue now holds a pair that collides. A shared
 * key must be refused outright rather than resolved in favour of one of its claimants.
 */
class LockCatalogTest {

    private static final String ONE_LOCK = """
            lock 001 | 4 plates | untrained
              init  [3, -1, 0, -3]
              conn  0:2I  2:1N,3I

            """;

    @Test
    void recallReturnsTheConnectionsThatWereRecorded(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, ONE_LOCK));

        Connection[][] got = catalog.recall(4, new int[] {3, -1, 0, -3}).orElseThrow();

        assertEquals(List.of(new Connection(2, INVERTED)), List.of(got[0]));
        assertEquals(List.of(), List.of(got[1]));
        assertEquals(List.of(new Connection(1, NORMAL), new Connection(3, INVERTED)), List.of(got[2]));
        assertEquals(List.of(), List.of(got[3]));
    }

    @Test
    void aStateNothingWasRecordedAtIsNotRecalled(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, ONE_LOCK));

        assertTrue(catalog.recall(4, new int[] {3, -1, 0, -2}).isEmpty(), "one offset differs");
        assertTrue(catalog.recall(5, new int[] {3, -1, 0, -3, 0}).isEmpty(), "wrong plate count");
        assertTrue(catalog.recall(4, new int[] {3, -1, 0}).isEmpty(), "state shorter than the lock");
    }

    /** A row the reader could not resolve is not a state, so it cannot be a key. */
    @Test
    void anUnreadableOffsetNeverMatches(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, ONE_LOCK));

        assertTrue(catalog.recall(4, new int[] {3, LockModel.UNKNOWN, 0, -3}).isEmpty());
    }

    /**
     * The reload loop, which is the whole point of keeping the catalogue in memory: solve a lock,
     * reload the save, press F8 on the same chest, and it is recognised - with nothing re-read from
     * disk in between.
     */
    @Test
    void aLockRememberedMidSessionIsRecalledAtOnce(@TempDir Path dir) throws Exception {
        Path empty = dir.resolve("nothing-yet.txt");
        LockCatalog catalog = LockCatalog.ofFiles(empty);
        int[] state = {1, 1, -2, 0, 3};
        Connection[][] learned = {{new Connection(3, NORMAL)}, {}, {}, {new Connection(0, INVERTED)}, {}};
        assertTrue(catalog.recall(5, state).isEmpty(), "nothing is known yet");

        catalog.remember(5, state, learned);

        assertEquals(List.of(new Connection(3, NORMAL)),
                List.of(catalog.recall(5, state).orElseThrow()[0]));
        assertFalse(Files.exists(empty), "remember() must not touch disk - LockHistory owns that");
    }

    /**
     * The same chest can be known twice over: once from an untrained character, once from a master
     * who saw two fewer plate connections. The fuller model wins, because a move legal under a
     * superset of the truth is legal under the truth - so recalling it can never strain.
     */
    @Test
    void theMostConnectedModelWinsWhenOneStateIsKnownTwice(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));
        int[] state = {0, 1, -1, 2};
        Connection[][] master = {{new Connection(2, NORMAL)}, {}, {}, {}};
        Connection[][] untrained = {
            {new Connection(2, NORMAL)}, {new Connection(3, INVERTED)}, {}, {new Connection(0, NORMAL)}
        };

        catalog.remember(4, state, master);
        catalog.remember(4, state, untrained);
        Connection[][] first = catalog.recall(4, state).orElseThrow();
        catalog.remember(4, state, master); // and the thinner one cannot displace it afterwards
        Connection[][] second = catalog.recall(4, state).orElseThrow();

        assertEquals(1, first[1].length, "the fuller model must win: " + List.of(first[1]));
        assertEquals(1, second[1].length, "and keep winning: " + List.of(second[1]));
    }

    /**
     * Two chests that start at the same offsets, which is not hypothetical: a real four-plate chest
     * at {@code [3, 3, -3, -2]} was recognised as the bundled {@code lock 014} and the plan's first
     * move strained on the pick.
     *
     * <p>Neither model contains the other, and no amount of lockpicking training turns one into the
     * other - training only removes connections - so these are two locks, not one lock seen twice.
     * The key is refused outright. Keeping whichever had more connections, as this used to, is a coin
     * flip that hands the session a model of the wrong chest, which is neither superset nor subset and
     * so <i>can</i> strain; and it poisons the key for both chests permanently.
     */
    @Test
    void aStateTwoDifferentChestsShareIsNeverRecalled(@TempDir Path dir) {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));
        int[] shared = {3, 3, -3, -2};
        Connection[][] lock014 = {
            {new Connection(1, INVERTED), new Connection(2, INVERTED), new Connection(3, NORMAL)},
            {new Connection(2, NORMAL)}, {},
            {new Connection(1, INVERTED), new Connection(2, INVERTED)},
        };
        Connection[][] theOtherChest = {{}, {}, {new Connection(0, NORMAL)}, {}};

        catalog.remember(4, shared, lock014);
        assertTrue(catalog.recall(4, shared).isPresent(), "one claimant is still a hit");
        catalog.remember(4, shared, theOtherChest);

        assertTrue(catalog.recall(4, shared).isEmpty(), "two claimants means neither is recalled");
        assertEquals(1, catalog.ambiguousKeys());
        assertEquals(2, catalog.size(), "both chests are still known...");
        assertEquals(1, catalog.matching(4, Map.of(2, theOtherChest[2])).size(),
                "...and a single probed row still tells them apart");
    }

    /** Once a key is known to name two chests, nothing said afterwards can un-share it. */
    @Test
    void anAmbiguousKeyStaysAmbiguous(@TempDir Path dir) {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));
        int[] shared = {0, 1, -1, 2};
        Connection[][] one = {{new Connection(2, NORMAL)}, {}, {}, {}};
        Connection[][] other = {{}, {new Connection(0, INVERTED)}, {}, {}};

        catalog.remember(4, shared, one);
        catalog.remember(4, shared, other);
        catalog.remember(4, shared, one); // and again, from a later solve of the first chest

        assertTrue(catalog.recall(4, shared).isEmpty());
        assertEquals(1, catalog.ambiguousKeys());
    }

    /** The same chest recorded twice is not a collision at all, and must not be treated as one. */
    @Test
    void rememberingTheSameModelTwiceIsNotACollision(@TempDir Path dir) {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));
        int[] state = {0, 1, -1, 2};
        Connection[][] model = {{new Connection(2, NORMAL)}, {}, {}, {}};

        catalog.remember(4, state, model);
        catalog.remember(4, state, new Connection[][] {{new Connection(2, NORMAL)}, {}, {}, {}});

        assertEquals(0, catalog.ambiguousKeys());
        assertTrue(catalog.recall(4, state).isPresent());
    }

    /**
     * The way back for a run that did not start on a pristine lock: the offsets no longer key
     * anything, but one row this run probed for itself already narrows the candidates hard.
     */
    @Test
    void matchingNarrowsTheCandidatesByRowsThisRunObserved(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, """
                lock 001 | 4 plates
                  init  [3, 3, -3, -3]
                  conn  0:2I  1:0I

                lock 002 | 4 plates
                  init  [1, 1, 1, 1]
                  conn  0:2I  3:1N

                lock 003 | 4 plates
                  init  [2, 2, 2, 2]
                  conn  0:3N

                """));

        assertEquals(3, catalog.matching(4, Map.of()).size(), "nothing observed rules nothing out");
        assertEquals(2, catalog.matching(4, Map.of(0, new Connection[] {new Connection(2, INVERTED)}))
                .size(), "two locks drag 2 inverted from plate 0");
        assertEquals(1, catalog.matching(4, Map.of(
                0, new Connection[] {new Connection(2, INVERTED)},
                3, new Connection[] {new Connection(1, NORMAL)})).size(), "a second row settles it");
        assertEquals(0, catalog.matching(4, Map.of(2, new Connection[] {new Connection(0, NORMAL)}))
                .size(), "a row no remembered lock has");
    }

    /** A row the session built plate-by-plate must match one a file listed in any order. */
    @Test
    void rowsAreComparedRegardlessOfTheOrderTheyAreListedIn(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, """
                lock 001 | 4 plates
                  init  [0, 0, 0, 1]
                  conn  1:3I,0N

                """));

        assertEquals(1, catalog.matching(4, Map.of(1, new Connection[] {
            new Connection(0, NORMAL), new Connection(3, INVERTED)})).size());
    }

    /**
     * Anything that does not parse cleanly is dropped rather than guessed at. Each block below is
     * broken in a different way and none of them may reach the session as a fact.
     */
    @Test
    void malformedEntriesAreSkippedNotGuessedAt(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, """
                lock 001 | 4 plates
                  init  [0, 0, 0, 0]
                  conn  0:?  1:2N

                lock 002 | 4 plates
                  init  [1, 0, 0, 0]
                  conn  0:9N

                lock 003 | 4 plates
                  init  [2, 0, 0, 0]
                  conn  1:1N

                lock 004 | 4 plates
                  init  [3, 0, 0, 0]
                  conn  0:2X

                lock 005 | 4 plates
                  init  [0, 0, 0, 9]
                  conn  0:2N

                lock 006 | 4 plates
                  init  [0, 0, 0]
                  conn  0:2N

                lock 007 | 9 plates
                  init  [0, 0, 0, 0, 0, 0, 0, 0, 0]
                  conn  0:2N

                lock 008 | 4 plates
                  init  [-1, 0, 0, 0]
                  conn  0:2N,2I

                lock 009 | 4 plates
                  init  [-2, 0, 0, 0]
                  conn  0:2N  0:3N

                """));

        assertEquals(0, catalog.size(), "every block above is broken in some way");
    }

    /**
     * Every other shape of damage, one block each. None may reach the session, and none may throw -
     * this file is edited by hand and appended to by a running tool, so it will be malformed one day.
     */
    @Test
    void everyOtherKindOfDamageIsSkippedToo(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, """
                lock x | many plates | untrained
                  init  [0, 0, 0, 0]
                  conn  0:2N

                lock 002 | 4 plates of brass | untrained
                  init  [1, 0, 0, 0]
                  conn  0:2N

                lock 003 | 4 plates
                  init  2, 0, 0, 0
                  conn  0:2N

                lock 004 | 4 plates
                  init  []
                  conn  0:2N

                lock 005 | 4 plates
                  init  [x, 0, 0, 0]
                  conn  0:2N

                lock 006 | 4 plates
                  init  [3, 0, 0, 0]
                  conn  0-2N

                lock 007 | 4 plates
                  init  [-3, 0, 0, 0]
                  conn  x:2N

                lock 008 | 4 plates
                  init  [-1, 0, 0, 0]
                  conn  0:xN

                lock 009 | 4 plates
                  init  [-2, 0, 0, 0]

                """));

        assertEquals(0, catalog.size(), "every block above is unusable");
    }

    /** A lock where nothing drags anything is a real lock, and reads back as one. */
    @Test
    void aLockWhereNothingDragsAnythingRoundTrips(@TempDir Path dir) throws Exception {
        LockCatalog catalog = LockCatalog.ofFiles(write(dir, """
                lock 001 | 4 plates
                  init  [1, 2, 3, -1]
                  conn  (none)

                """));

        Connection[][] got = catalog.recall(4, new int[] {1, 2, 3, -1}).orElseThrow();

        assertEquals(4, got.length);
        for (Connection[] row : got) {
            assertEquals(0, row.length);
        }
    }

    /** A model with a row that was never probed is not a model, so it is not remembered. */
    @Test
    void anIncompleteModelIsNotRemembered(@TempDir Path dir) {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));

        catalog.remember(3, new int[] {0, 0, 0}, new Connection[][] {{}, null, {}});
        catalog.remember(3, new int[] {0, 0, 0}, null);
        catalog.remember(3, new int[] {0, 0, 0}, new Connection[][] {{}, {}}); // wrong plate count

        assertEquals(0, catalog.size());
    }

    /** A model with no usable key is still worth knowing - {@code matching} can still find it. */
    @Test
    void aModelWithNoUsableStateIsKeptForRowMatching(@TempDir Path dir) {
        LockCatalog catalog = LockCatalog.ofFiles(dir.resolve("none.txt"));
        Connection[][] model = {{new Connection(1, NORMAL)}, {}, {}};

        catalog.remember(3, null, model);
        catalog.remember(3, new int[] {0, LockModel.UNKNOWN, 0}, model);
        catalog.remember(3, new int[] {0, 0}, model); // state shorter than the lock

        assertEquals(1, catalog.size(), "the model is known...");
        assertTrue(catalog.recall(3, new int[] {0, 0, 0}).isEmpty(), "...but keyed by nothing");
        assertEquals(1, catalog.matching(3, Map.of(0, model[0])).size());
    }

    /** A source that is not there at all is simply no knowledge - never an exception. */
    @Test
    void aMissingSourceIsNotAnError(@TempDir Path dir) {
        assertEquals(0, LockCatalog.ofFiles(dir.resolve("never-written.txt")).size());
    }

    @Test
    void anUnreadableSourceCostsALineNotTheRun(@TempDir Path dir) {
        // A directory exists but cannot be read as lines, which is the shape of every real I/O failure.
        String log = Stdout.capturing(() -> assertEquals(0, LockCatalog.ofFiles(dir).size()));

        assertTrue(log.contains("could not read"), log);
    }

    /** The production constructor reads real paths; whatever it finds, it must not throw. */
    @Test
    void theProductionCatalogueLoadsWithoutComplaint() {
        assertTrue(Stdout.quietly(() -> new LockCatalog().size()) >= 100,
                "bundled + local history should be at least the shipped corpus");
    }

    // --- the catalogue that actually ships ---

    @Test
    void theBundledCatalogueLoads() {
        assertTrue(LockCatalog.bundledOnly().size() >= 100,
                "the shipped known-locks.txt should hold the whole corpus");
    }

    /**
     * Every bundled lock must be recallable by the state it was recorded at - unless another chest
     * shows the same offsets, in which case neither may be. This is the shipped artifact's own gate:
     * a hand-edit that breaks a block would otherwise only show up as a lock quietly no longer being
     * recognised.
     */
    @Test
    void everyBundledLockRecallsItselfUnlessAnotherChestSharesItsKey() throws Exception {
        List<LockCatalog.Entry> entries = bundledEntries();
        LockCatalog catalog = LockCatalog.bundledOnly();
        Map<String, Integer> claimants = claimantsPerKey(entries);

        assertEquals(catalog.size(), entries.size(), "every block parsed is a distinct lock");
        for (LockCatalog.Entry e : entries) {
            String key = e.n() + Arrays.toString(e.state());
            boolean recalled = catalog.recall(e.n(), e.state()).isPresent();
            if (claimants.get(key) > 1) {
                assertFalse(recalled, "two chests answer to " + key + ", so neither may be recalled");
            } else {
                assertTrue(recalled, "not recalled: " + Arrays.toString(e.state()));
            }
        }
        assertEquals(claimants.values().stream().filter(c -> c > 1).count(),
                catalog.ambiguousKeys(), "every shared key must be marked, and nothing else");
    }

    /**
     * The recall key is a measurement, not a theorem - and the shipped catalogue is where that stops
     * being abstract. Two chests do show the same offsets at F8, and when they do the catalogue must
     * refuse the key rather than pick one of them: a model of the wrong chest is neither superset nor
     * subset of the truth, so unlike a stale one it <b>can</b> strain the pick. Both chests stay
     * findable by {@link LockCatalog#matching}, which is keyed on rows a run has actually probed.
     *
     * <p>It must also stay <i>rare</i>. Every shared key is a chest that has to be learned the long
     * way, so a catalogue where this became common would be quietly losing the feature it exists for -
     * which is what this asserts, rather than that it never happens.
     */
    @Test
    void aStateTwoBundledChestsShareIsRefusedRatherThanGuessedAt() throws Exception {
        List<LockCatalog.Entry> entries = bundledEntries();
        LockCatalog catalog = LockCatalog.bundledOnly();
        Map<String, Integer> claimants = claimantsPerKey(entries);

        for (Map.Entry<String, Integer> key : claimants.entrySet()) {
            if (key.getValue() <= 1) {
                continue;
            }
            List<LockCatalog.Entry> both = entries.stream()
                    .filter(e -> (e.n() + Arrays.toString(e.state())).equals(key.getKey()))
                    .toList();
            for (LockCatalog.Entry e : both) {
                assertTrue(catalog.recall(e.n(), e.state()).isEmpty(), "refused: " + key.getKey());
                assertEquals(1, catalog.matching(e.n(), rowsOf(e)).size(),
                        "its own rows must still name it: " + key.getKey());
            }
        }
        assertTrue(catalog.ambiguousKeys() * 20 <= entries.size(),
                "shared keys must stay rare, or recall is quietly losing its point: "
                        + catalog.ambiguousKeys() + " of " + entries.size());
    }

    /** How many distinct chests answer to each {@code (plate count, offsets)} key. */
    private static Map<String, Integer> claimantsPerKey(List<LockCatalog.Entry> entries) {
        Map<String, Set<String>> models = new HashMap<>();
        for (LockCatalog.Entry e : entries) {
            models.computeIfAbsent(e.n() + Arrays.toString(e.state()), k -> new HashSet<>())
                    .add(Arrays.deepToString(e.connections()));
        }
        Map<String, Integer> out = new HashMap<>();
        models.forEach((key, distinct) -> out.put(key, distinct.size()));
        return out;
    }

    /** An entry's whole model, as the map of probed rows {@code matching} takes. */
    private static Map<Integer, Connection[]> rowsOf(LockCatalog.Entry e) {
        Map<Integer, Connection[]> rows = new HashMap<>();
        for (int p = 0; p < e.n(); p++) {
            rows.put(p, e.connections()[p]);
        }
        return rows;
    }

    /** The shipped catalogue, read off the classpath exactly as the running app reads it. */
    private static List<LockCatalog.Entry> bundledEntries() throws Exception {
        try (var in = LockCatalog.class.getResourceAsStream(LockCatalog.BUNDLED)) {
            assertTrue(in != null, "known-locks.txt is missing from the jar");
            return LockCatalog.parse(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList());
        }
    }

    private static Path write(Path dir, String text) throws Exception {
        Path file = dir.resolve("locks.txt");
        Files.writeString(file, text);
        return file;
    }
}
