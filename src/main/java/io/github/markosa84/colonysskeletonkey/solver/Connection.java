package io.github.markosa84.colonysskeletonkey.solver;

import java.util.List;

/**
 * One directed drag link: moving the owning piece also slides {@code target}, the same way
 * ({@link Type#NORMAL}) or the opposite way ({@link Type#INVERTED}).
 *
 * <p>Links are per-mover and do not cascade: a dragged piece's own links do not fire.
 */
public record Connection(int target, Type type) {

    /**
     * Is {@code row} contained in {@code superset} - every link of the first present, unchanged, in
     * the second?
     *
     * <p>Small, but it is the discriminator the whole recall path turns on, so it lives here with the
     * rest of the row algebra rather than in whichever caller needed it first. The game's skill
     * mechanic only ever <b>removes</b> a plate connection (Trained one, Master another), so a lock
     * is always a subset of its untrained self. Two consequences, and they are opposite in kind:
     *
     * <ul>
     *   <li>A move legal under a superset is legal under the truth - fewer plates move, by the same
     *       deltas - which is why a remembered model can mispredict where the plates land and still
     *       never strain the pick.</li>
     *   <li>So a remembered row the lock contradicts by having <i>more</i> in it cannot be this chest
     *       at any skill level. It is a different chest whose offsets happen to match: the offsets
     *       are a measured property of 203 real locks, not a theorem, and they do collide.</li>
     * </ul>
     *
     * <p>Order-insensitive, because nothing in the catalogue's file format guarantees one.
     */
    public static boolean rowContains(Connection[] superset, Connection[] row) {
        return superset != null && row != null && List.of(superset).containsAll(List.of(row));
    }

    /** Is every row of {@code model} contained in {@code superset}'s? See {@link #rowContains}. */
    public static boolean contains(Connection[][] superset, Connection[][] model) {
        if (superset == null || model == null || superset.length != model.length) {
            return false;
        }
        for (int p = 0; p < model.length; p++) {
            if (!rowContains(superset[p], model[p])) {
                return false;
            }
        }
        return true;
    }

    /** How a dragged piece follows the piece you moved. */
    public enum Type {
        /** The dragged piece slides the SAME direction as the piece you moved. */
        NORMAL(+1),
        /** The dragged piece slides the OPPOSITE direction to the piece you moved. */
        INVERTED(-1);

        private final int sign;

        Type(int sign) {
            this.sign = sign;
        }

        /** Multiplier applied to the mover's direction. */
        public int sign() {
            return sign;
        }
    }
}
