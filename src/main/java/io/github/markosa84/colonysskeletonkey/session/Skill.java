package io.github.markosa84.colonysskeletonkey.session;

import java.util.Optional;

/**
 * The character's lockpicking skill, which changes the rules of the minigame itself.
 *
 * <table>
 *   <caption>Level breakdown</caption>
 *   <tr><th>Level</th><th>Strains per pick</th><th>Puzzle resets when a pick breaks?</th></tr>
 *   <tr><td>0 Untrained</td><td>2</td><td><b>yes</b></td></tr>
 *   <tr><td>1 Basic</td><td>4</td><td>no</td></tr>
 *   <tr><td>2 Master</td><td>6</td><td>no</td></tr>
 * </table>
 *
 * <p>Levels 1 and 2 also delete a connection between two plates, which only makes locks easier and
 * needs no special handling.
 *
 * <h2>This is an observation, not a setting</h2>
 * The tool never asks the player what level they are, and it must not: the character can train
 * lockpicking at any moment, so an answer given once is a lie soon afterwards. It is also
 * unnecessary. A broken pick is <b>seen</b> - {@code Slider} watches the remaining-lockpicks
 * counter around every move that could strain - so {@code LockSession} counts picks by observing
 * them, and nothing in the control flow needs a level at all.
 *
 * <p>What the level does is <i>fall out</i> of the same observation. The strains a pick survived
 * before it broke <b>is</b> that character's strains-per-pick, and a break that also slid every
 * plate home can only have happened at level 0. So the session reports the level it saw, for the
 * player's information, and forgets it when the run ends. See {@link #fromStrainsPerPick}.
 */
public enum Skill {
    UNTRAINED(2, true),
    BASIC(4, false),
    MASTER(6, false);

    private final int mistakes;
    private final boolean resetsOnBreak;

    Skill(int mistakes, boolean resetsOnBreak) {
        this.mistakes = mistakes;
        this.resetsOnBreak = resetsOnBreak;
    }

    /** Strains this character's pick survives before it breaks. */
    public int mistakes() {
        return mistakes;
    }

    /** True if a broken pick slides every plate back to its original position - i.e. is visible. */
    public boolean resetsOnBreak() {
        return resetsOnBreak;
    }

    /**
     * The level a pick that broke after {@code strains} strains belongs to, or empty if that count
     * matches no level.
     *
     * <p>Empty is not an error and not a reason to guess. A pick carries its damage <i>between</i>
     * locks, so the very first pick a session breaks may have arrived already half-worn and will
     * break early - only a pick that a session watched from full to broken gives a trustworthy
     * count. Nothing depends on the answer, so an unrecognised count is simply reported as the raw
     * number of strains it took.
     */
    public static Optional<Skill> fromStrainsPerPick(int strains) {
        for (Skill s : values()) {
            if (s.mistakes == strains) return Optional.of(s);
        }
        return Optional.empty();
    }
}
