package io.github.markosa84.colonysskeletonkey.control;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run accounting {@code AutoLockpick} prints after every F8. It is the only evidence anyone has
 * for where a run's wall clock went, so its arithmetic - what counts as watching, what as polling
 * sleep, and what is left unaccounted - is worth pinning exactly.
 */
class TelemetryTest {

    private static final long MS = 1_000_000L;
    private static final long S = 1_000_000_000L;

    @Test
    void aRunWithNoSlidesHasNothingToReport() {
        Telemetry t = new Telemetry();

        assertEquals(0, t.slides());
        assertEquals("", t.summary(10 * S), "no slide, no breakdown - not a line of zeroes");
    }

    /**
     * The whole report, on numbers that divide cleanly. Watching is the wall clock inside the poll
     * loop; the reads are the grabs inside that loop, so the rest of the watching is polling sleep;
     * and whatever the slider did not account for is reported rather than quietly dropped.
     */
    @Test
    void theSummaryAccountsForEveryNanosecondOfTheRun() {
        Telemetry t = new Telemetry();
        t.countSlide();
        t.countSlide();
        t.addWatch(1 * S);
        t.addWatch(3 * S); // the worst wait
        t.addRead(500 * MS);
        t.addRead(500 * MS);
        t.addCounter(200 * MS);
        t.addKeys(100 * MS);

        // 4.0s watching + 0.2s counter + 0.1s keys = 4.3s accounted, of a 10s run.
        String expected = String.format(
                "  2 slides in 10.0s (5000ms each). Watching them settle: 4.0s in 2 waits"
                        + " (2000ms each, worst 3.0s).%n"
                        + "  Of that, 2 frame reads cost 1.0s (500ms each) and 3.0s went to polling"
                        + " sleeps. Counter: 1 grabs, 0.2s. Keys: 0.1s. Unaccounted: 5.7s.");

        assertEquals(expected, t.summary(10 * S));
        assertEquals(2, t.slides());
    }

    /** A slide that somehow never watched or read must not divide by zero. */
    @Test
    void aSlideWithNoWaitsAndNoReadsStillReports() {
        Telemetry t = new Telemetry();
        t.countSlide();

        String summary = t.summary(1 * S);

        assertTrue(summary.contains("1 slides in 1.0s (1000ms each)"), summary);
        assertTrue(summary.contains("0.0s in 0 waits (0ms each, worst 0.0s)"), summary);
        assertTrue(summary.contains("0 frame reads cost 0.0s (0ms each)"), summary);
    }

    /** Each F8 press reports its own run: nothing may survive the reset. */
    @Test
    void resetForgetsTheWholeRun() {
        Telemetry t = new Telemetry();
        t.countSlide();
        t.addWatch(5 * S);
        t.addRead(2 * S);
        t.addCounter(1 * S);
        t.addKeys(1 * S);

        t.reset();

        assertEquals(0, t.slides());
        assertEquals("", t.summary(10 * S));
    }

    /** The worst wait is the maximum, not the last one. */
    @Test
    void theWorstWaitIsTheLongestOneEverSeen() {
        Telemetry t = new Telemetry();
        t.countSlide();
        t.addWatch(4 * S);
        t.addWatch(1 * S);

        assertTrue(t.summary(10 * S).contains("worst 4.0s"), t.summary(10 * S));
    }
}
