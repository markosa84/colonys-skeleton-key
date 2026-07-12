package io.github.markosa84.colonysskeletonkey.control;

import java.util.Arrays;

import io.github.markosa84.colonysskeletonkey.session.MoveExecutor;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;
import io.github.markosa84.colonysskeletonkey.solver.Move;

/**
 * The only place a plate is ever moved. Presses one key, watches the lock, and reports what the
 * game actually did. Everything above it - discovery and solving alike - reasons from those
 * observations rather than from hope.
 *
 * <h2>What the game does, measured</h2>
 * <ul>
 *   <li>A slide's plates begin moving ~110ms after the key and stop ~210ms after it; the reader
 *       sees a stable, correct state by ~270ms. So an unchanged lock means nothing before
 *       ~140ms.</li>
 *   <li>A <b>rejected</b> move never moves a plate, and <b>does not block input</b>: a legal slide
 *       sent 0ms after a strain still lands. The shake is invisible to the reader - no unreadable
 *       frames, no offset change - so a strain is exactly "the state did not change".</li>
 *   <li>Slides are <b>queued</b>, not dropped: six sent back-to-back at a 0ms gap all landed.
 *       Waiting between keys is therefore about <i>observing</i> the outcome, not about being
 *       heard.</li>
 *   <li>Input sent while the lock animates a <b>reset</b> is discarded. It takes seconds: every
 *       plate slides home in parallel, so it lasts as long as the furthest-travelled plate
 *       needs.</li>
 *   <li>A broken pick resets the puzzle <b>only at skill level 0</b>. Above that it changes
 *       nothing on screen, so {@link Outcome#RESET} simply never fires and the lock is left
 *       exactly where it was. Nothing here depends on knowing the player's level.</li>
 * </ul>
 *
 * <h2>Transient vs. persistent unreadability</h2>
 * A plate that is mid-slide reads {@link LockModel#UNKNOWN} for ~300ms - that means "still
 * moving". But a read can also fail in a <i>settled</i> configuration. The one cause diagnosed
 * live (a difficulty-4 chest whose arch-gap shadow the old hole walk mistook for a seventh hole)
 * is since fixed in the reader itself; this tolerance stays for whatever artifact shows up next.
 * The tell is time: nothing animates for longer than {@code Timing#settleMinMs}, so a state that
 * has stopped changing but keeps an unreadable plate is settled, and after
 * {@code Timing#partialAfterMs} it is returned as such - with those plates still
 * {@code UNKNOWN} - rather than waited on forever. A broken pick still shows
 * up nowhere in the lock above skill level 0, so it is read from the lockpick counter
 * ({@link LockPoller#pickFingerprint}) rather than guessed at from the plates.
 */
public final class Slider implements MoveExecutor {

    /**
     * The lock's animation contract. {@link #GAME} holds the values measured against the live
     * game; tests inject near-zeros so the polling loop runs instantaneously against scripted
     * frames.
     */
    public record Timing(int settleMinMs, int breakAnimationMs, int resetMaxMs, int pollMs,
                         int stillFrames, int partialAfterMs) {

        /**
         * Measured: a settled state is trusted only 300ms after the key (nothing has moved
         * before ~90ms, and a stable correct state shows by ~270ms); the pick-replacement
         * animation discards every key for ~5s; nothing the lock ever does outlasts 12s; a
         * sample already costs ~30ms (capture ~23ms + read ~8ms) so polls barely pause; two
         * consecutive identical readable frames count as still; and a partially readable state
         * that has not changed for 1.5s is settled - waiting will not resolve its unreadable
         * rows - not still-moving.
         */
        public static final Timing GAME = new Timing(300, 5000, 12_000, 5, 2, 1500);
    }

    private final LockPoller poller;
    private final KeySender keys;
    private final Timing timing;
    private final Telemetry telemetry;
    /** Last known lockpick-counter fingerprint, valid while {@link #picksKnown}. */
    private long picks;
    private boolean picksKnown;

    public Slider(LockPoller poller, KeySender keys, Timing timing) {
        this(poller, keys, timing, new Telemetry());
    }

    public Slider(LockPoller poller, KeySender keys, Timing timing, Telemetry telemetry) {
        this.poller = poller;
        this.keys = keys;
        this.timing = timing;
        this.telemetry = telemetry;
    }

    /** Where this slider's time went. Live-run accounting; the tests ignore it. */
    public Telemetry telemetry() {
        return telemetry;
    }

    /**
     * Selects the plate named by {@code move}, slides it once, and waits until the lock stops
     * moving.
     *
     * <p>{@code cur} is what the lock read before the key. The returned outcome is the truth,
     * whatever the caller expected: a move that the model swore was legal can still come back
     * {@link Outcome#UNCHANGED}. The returned state may contain {@link LockModel#UNKNOWN} for
     * plates whose row is hidden in the settled configuration - except for
     * {@link Outcome#UNCHANGED}, which returns the caller's own {@code cur}: moves are atomic,
     * so a visible unmoved mover means <i>nothing</i> moved and the pre-move state still holds.
     *
     * <p>The lockpick counter is only read when the move might have <b>strained</b> - a pick breaks
     * on nothing else, and a strain is exactly "the state did not change". A move that landed
     * cleanly is therefore proof that the counter did not move either, and grabbing it would cost a
     * screen capture (~14ms at 4K) to learn what is already known.
     */
    @Override
    public Observation play(int n, int[] cur, Move move) {
        int p = move.plate();
        int dir = move.dir();
        telemetry.countSlide();
        long picksBefore = knownPicks(); // sampled before the key, so a first-move break is seen
        long navFrom = System.nanoTime();
        keys.navigateTo(p); // selection changes are instant and never queued behind an animation
        telemetry.addKeys(System.nanoTime() - navFrom);
        long pressed = System.nanoTime();
        keys.slide(dir);
        telemetry.addKeys(System.nanoTime() - pressed);

        int[] settled = quiesce(n, pressed, timing.settleMinMs());
        // A legal move shifts the mover by exactly one step and drags nothing further. Anything
        // else means the game did something we did not ask for; say so rather than assume.
        boolean ourMove = LockModel.isComplete(settled)
                && !Arrays.equals(settled, cur)
                && settled[p] - cur[p] == dir
                && isSingleStep(cur, settled);
        if (ourMove) {
            return new Observation(Outcome.MOVED, settled, false); // landed: no strain, no break
        }
        picks = fingerprint();
        boolean broke = picks != picksBefore;

        if (!broke) {
            if (LockModel.isComplete(settled)) {
                if (Arrays.equals(settled, cur)) {
                    return new Observation(Outcome.UNCHANGED, settled, false); // a plain strain
                }
                return new Observation(Outcome.RESET, settled, false);
            }
            // Partially observed: a row is hidden in the settled configuration.
            if (readableEqual(settled, cur)) {
                // Moves are atomic: with the mover (and everything else visible) unchanged, the
                // move was rejected and the hidden plates did not move either - the caller's
                // full pre-move state still holds. (A hidden mover with no visible change is
                // ambiguous; if it did move, later observations contradict and correct it.)
                return new Observation(Outcome.UNCHANGED, cur.clone(), false);
            }
            return new Observation(Outcome.MOVED, settled, false); // session fills or rolls back
        }
        // The pick broke. At level 0 that resets the puzzle, which may already have started (and been
        // waited out by quiesce) or may still be coming. Either way, sit out the pick-replacement
        // animation before re-reading, because the game discards everything pressed into it.
        int[] recovered = awaitBreak(n);
        Outcome outcome = Arrays.equals(recovered, cur) ? Outcome.UNCHANGED : Outcome.RESET;
        return new Observation(outcome, recovered, true);
    }

    /**
     * Waits out the pick-replacement animation and re-reads the lock.
     *
     * <p>The game discards input while it plays - measured: two slides sent during it simply
     * vanished, which is why a second break appeared to need eight strains rather than six.
     */
    private int[] awaitBreak(int n) {
        sleep(timing.breakAnimationMs());
        return quiesce(n, System.nanoTime(), timing.settleMinMs());
    }

    /** Reads a lock that should not be moving, tolerating the tail of an animation. */
    @Override
    public int[] settle(int n) {
        // Time passes here that this slider did not cause - a fresh session, a recovery pause - so
        // whatever it last knew about the lockpick counter is no longer worth trusting.
        picksKnown = false;
        return quiesce(n, System.nanoTime(), timing.settleMinMs());
    }

    /**
     * The lockpick counter as it stands, grabbing it only if we do not already know. We know it
     * whenever every move since the last grab landed cleanly, because only a strain can spend a
     * pick.
     */
    private long knownPicks() {
        if (!picksKnown) {
            picks = fingerprint();
            picksKnown = true;
        }
        return picks;
    }

    /**
     * Polls until the lock is still: fully readable and identical for {@code timing.stillFrames()}
     * frames (and at least {@code minMs} passed), or - failing that - <i>unchanged including its
     * hidden plates</i> for {@code timing.partialAfterMs()}, which is a settled-and-occluded
     * state, returned with its {@link LockModel#UNKNOWN} entries. A state with nothing readable
     * at all is never returned; it times out into {@link UnreadableFrame} after
     * {@code timing.resetMaxMs()}.
     *
     * <p>The stillness-plus-time condition is load-bearing: stillness alone is satisfied by
     * frames taken before the game even started animating, which is how a successful move once
     * got recorded as a strain.
     */
    private int[] quiesce(int n, long since, int minMs) {
        long enteredAt = System.nanoTime();
        try {
            return watch(n, since, minMs);
        } finally {
            telemetry.addWatch(System.nanoTime() - enteredAt);
        }
    }

    private int[] watch(int n, long since, int minMs) {
        int[] fullPrev = null;
        int fullSame = 0;
        int[] rawPrev = null;
        int rawSame = 0;
        while (millisSince(since) < timing.resetMaxMs()) {
            int[] state = readLock(n);
            if (Arrays.equals(state, rawPrev)) {
                rawSame++;
            } else {
                rawPrev = state;
                rawSame = 0;
            }
            if (LockModel.isComplete(state)) {
                if (Arrays.equals(state, fullPrev)) {
                    if (++fullSame >= timing.stillFrames() - 1 && millisSince(since) >= minMs) {
                        return state;
                    }
                } else {
                    fullPrev = state;
                    fullSame = 0;
                }
            } else {
                fullPrev = null;
                fullSame = 0;
                if (anyReadable(state)
                        && rawSame >= timing.stillFrames() - 1
                        && millisSince(since) >= Math.max(minMs, timing.partialAfterMs())) {
                    return state; // settled, with a hidden row - nothing animates this long
                }
            }
            sleep(timing.pollMs());
        }
        throw new UnreadableFrame();
    }

    /** One poll: a lock-box grab plus its decode - the unit a run is really built out of. */
    private int[] readLock(int n) {
        long from = System.nanoTime();
        try {
            return poller.readLock(n);
        } finally {
            telemetry.addRead(System.nanoTime() - from);
        }
    }

    private long fingerprint() {
        long from = System.nanoTime();
        try {
            return poller.pickFingerprint();
        } finally {
            telemetry.addCounter(System.nanoTime() - from);
        }
    }

    /** True if no plate changed by more than one step, ignoring hidden entries on either side. */
    static boolean isSingleStep(int[] before, int[] after) {
        for (int i = 0; i < before.length; i++) {
            if (before[i] == LockModel.UNKNOWN || after[i] == LockModel.UNKNOWN) continue;
            if (Math.abs(after[i] - before[i]) > 1) return false;
        }
        return true;
    }

    /** True if every readable entry of {@code partial} equals {@code reference}'s. */
    private static boolean readableEqual(int[] partial, int[] reference) {
        for (int i = 0; i < partial.length; i++) {
            if (partial[i] != LockModel.UNKNOWN && partial[i] != reference[i]) return false;
        }
        return true;
    }

    private static boolean anyReadable(int[] state) {
        for (int v : state) {
            if (v != LockModel.UNKNOWN) return true;
        }
        return false;
    }

    private static long millisSince(long nanos) {
        return (System.nanoTime() - nanos) / 1_000_000L;
    }

    private static void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
