package io.github.markosa84.colonysskeletonkey.solver;

/**
 * One slide: select piece {@code plate}, then slide it one step in {@code dir}.
 *
 * <p>Direction convention, shared with the whole tool: {@code +1} = LEFT = the game's {@code A}
 * key, {@code -1} = RIGHT = {@code D}. Sliding left raises the plate's offset by one, which is
 * exactly what {@link LockSolver#applyMove} does with {@code dir}.
 */
public record Move(int plate, int dir) {}
