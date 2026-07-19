package io.github.markosa84.colonysskeletonkey.control;

import java.util.Locale;

/**
 * Where a session's wall clock actually goes, counted rather than guessed.
 *
 * <p>A slide costs far more than the key that starts it. The game animates for ~300ms, and the
 * {@link Slider} watches that animation out one screen grab at a time - each grab ~27ms for the
 * lock's box at 4K, ~14ms for the lockpick counter, measured on an idle desktop and worse while the
 * game is rendering. A hard six-plate lock needs 30-odd slides, so what looks like a per-frame
 * detail is most of the run. This class breaks a run down into the phases a speedup could actually
 * attack:
 *
 * <ul>
 *   <li><b>watching</b> - inside {@link Slider}'s poll loop, waiting for the lock to settle. Its
 *       floor is the animation; anything above that floor is polling overhead.</li>
 *   <li><b>frame reads</b> - the grabs inside that loop (the dominant part of watching).</li>
 *   <li><b>counter grabs</b> - the lockpick-counter fingerprints, two per slide today.</li>
 *   <li><b>keys</b> - navigating the selection and pressing the slide.</li>
 *   <li><b>unaccounted</b> - everything else the session does: the full-screen grabs behind
 *       {@code detectPlateCount} and the settle reads, the cursor re-homing, the solver itself.</li>
 * </ul>
 *
 * <p>One session runs at a time on one thread, so none of this needs to be thread-safe.
 */
public final class Telemetry {

    private int slides;
    private int watches;
    private long watchNs;
    private long worstWatchNs;
    private int reads;
    private long readNs;
    private int counterGrabs;
    private long counterNs;
    private long keyNs;

    /** Forgets everything, so each F8 reports its own run. */
    public void reset() {
        slides = 0;
        watches = 0;
        watchNs = 0;
        worstWatchNs = 0;
        reads = 0;
        readNs = 0;
        counterGrabs = 0;
        counterNs = 0;
        keyNs = 0;
    }

    void countSlide() {
        slides++;
    }

    void addWatch(long ns) {
        watches++;
        watchNs += ns;
        worstWatchNs = Math.max(worstWatchNs, ns);
    }

    void addRead(long ns) {
        reads++;
        readNs += ns;
    }

    void addCounter(long ns) {
        counterGrabs++;
        counterNs += ns;
    }

    void addKeys(long ns) {
        keyNs += ns;
    }

    public int slides() {
        return slides;
    }

    /**
     * A two-line breakdown of a run of {@code totalNs}, or an empty string if nothing was ever
     * slid. Everything the {@link Slider} does is measured; whatever is left over is reported as
     * unaccounted rather than quietly dropped.
     */
    public String summary(long totalNs) {
        if (slides == 0) {
            return "";
        }
        long accountedNs = watchNs + counterNs + keyNs;
        long sleepNs = watchNs - readNs;
        // Locale.ROOT: a timing report is diagnostics, and "0.3s" must not become "0,3s" on a
        // machine whose locale says so - not least because the tests pin this text.
        return String.format(Locale.ROOT,
                "  %d slides in %s (%s each). Watching them settle: %s in %d waits"
                        + " (%s each, worst %s).%n"
                        + "  Of that, %d frame reads cost %s (%s each) and %s went to polling"
                        + " sleeps. Counter: %d grabs, %s. Keys: %s. Unaccounted: %s.",
                slides, secs(totalNs), millis(totalNs / slides),
                secs(watchNs), watches, millis(watchNs / Math.max(1, watches)), secs(worstWatchNs),
                reads, secs(readNs), millis(readNs / Math.max(1, reads)), secs(sleepNs),
                counterGrabs, secs(counterNs), secs(keyNs), secs(totalNs - accountedNs));
    }

    private static String secs(long ns) {
        return String.format(Locale.ROOT, "%.1fs", ns / 1e9);
    }

    private static String millis(long ns) {
        return (ns / 1_000_000L) + "ms";
    }
}
