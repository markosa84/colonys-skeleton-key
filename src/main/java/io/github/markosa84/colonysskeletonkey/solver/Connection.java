package io.github.markosa84.colonysskeletonkey.solver;

/**
 * One directed drag link: moving the owning piece also slides {@code target}, the same way
 * ({@link Type#NORMAL}) or the opposite way ({@link Type#INVERTED}).
 *
 * <p>Links are per-mover and do not cascade: a dragged piece's own links do not fire.
 */
public record Connection(int target, Type type) {

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
