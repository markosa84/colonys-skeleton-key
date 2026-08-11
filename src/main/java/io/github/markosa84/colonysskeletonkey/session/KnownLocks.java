package io.github.markosa84.colonysskeletonkey.session;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.markosa84.colonysskeletonkey.solver.Connection;

/**
 * Locks this tool has opened before, as a lookup the session can consult before it starts probing.
 *
 * <p>Measured over 186 real chests: <b>{@code (plate count, offsets at F8)} very nearly maps
 * one-to-one onto the connections</b> - one collision in 186 - and the same chest re-opened reports
 * byte-identical offsets and connections. So the state the lock is in when F8 is pressed is a sound
 * key, and a hit turns a run into a straight solve - measured over that corpus, 14% fewer slides and
 * every one of its strains gone.
 *
 * <p><b>That is a measurement, not a theorem, and the key does collide.</b> A four-plate lock has
 * 7^4 starting configurations and they are not evenly used - each end of the track is about twice as
 * likely as any other position - so as the catalogue grows, two chests eventually share one. The
 * chance a new four-plate chest lands on a key already remembered is about 1.5% and rising with every
 * lock opened; the shipped catalogue already holds one such pair, both starting at
 * {@code [3, 3, -3, -2]}. {@code LockCatalog} refuses a key once it knows two chests answer to it;
 * the session handles the rest.
 *
 * <p><b>A hit is a hypothesis, never a fact.</b> {@link LockSession} adopts a remembered model only
 * after checking it opens the lock from where the lock actually is, and every move it then plays is
 * verified against its prediction exactly as a probed model's moves are - a surprise costs one move
 * and drops back to discovery. What makes that cheap rather than merely safe is the game's skill
 * mechanic: Trained removes one plate connection and Master removes another, so a lock is a
 * <i>subset</i> of its untrained self, and a move that is legal under a superset model is legal under
 * any subset of it (fewer plates move, by the same deltas). A remembered model of <b>this chest</b>
 * can therefore leave the plan wrong, but it cannot strain the pick.
 *
 * <p>That covers the character's skill and nothing else. A memory of a <i>different</i> chest is
 * neither superset nor subset and can strain - which is precisely how the session recognises one: a
 * strain on a slide the memory called legal, or an observed row with a connection the memory lacks,
 * is impossible for the same chest at any level. Both make the run drop the memory whole.
 *
 * <p>This is a seam the session owns, like {@link LockView} and {@link MoveExecutor}, so the session
 * knows nothing about files. {@link #NONE} is the "remember nothing" implementation every test that
 * does not care about recall gets by default.
 */
public interface KnownLocks {

    /**
     * The connections remembered for a lock of {@code n} plates sitting at exactly {@code state}, if
     * any. Where several are known for one key, what is returned depends on how they differ: the same
     * chest recorded at different lockpicking levels gives the <b>most connected</b> of them, which is
     * the one that cannot strain; two genuinely different chests give <b>nothing</b>, because a coin
     * flip between them is worse than probing.
     */
    Optional<Connection[][]> recall(int n, int[] state);

    /**
     * Every remembered lock of {@code n} plates whose rows agree with {@code observedRows} - the
     * plates this run has already probed for itself, keyed by plate index.
     *
     * <p>This is the path back for a run that did not start on a pristine lock: press F8 again after a
     * failed attempt and the offsets no longer match anything, but one probed row is already enough to
     * cut the candidates hard (measured: a six-plate lock goes from 58 remembered locks to about four,
     * and is uniquely identified 39% of the time). The session adopts the result only when exactly one
     * candidate survives.
     */
    List<Connection[][]> matching(int n, Map<Integer, Connection[]> observedRows);

    /** Remembers nothing, so every run probes from scratch. */
    KnownLocks NONE = new KnownLocks() {

        @Override
        public Optional<Connection[][]> recall(int n, int[] state) {
            return Optional.empty();
        }

        @Override
        public List<Connection[][]> matching(int n, Map<Integer, Connection[]> observedRows) {
            return List.of();
        }
    };
}
