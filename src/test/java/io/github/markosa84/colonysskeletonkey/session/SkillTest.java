package io.github.markosa84.colonysskeletonkey.session;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The game's per-level rules, and reading a level back out of a pick that broke. */
class SkillTest {

    /** Pins the game rules: strains per pick, and whether a break is visible (resets the puzzle). */
    @Test
    void levelsCarryTheGamesRules() {
        assertEquals(2, Skill.UNTRAINED.mistakes());
        assertTrue(Skill.UNTRAINED.resetsOnBreak());
        assertEquals(4, Skill.BASIC.mistakes());
        assertFalse(Skill.BASIC.resetsOnBreak());
        assertEquals(6, Skill.MASTER.mistakes());
        assertFalse(Skill.MASTER.resetsOnBreak());
    }

    /** A pick that broke after n strains names the level: that is what n means. */
    @Test
    void aBrokenPicksStrainCountNamesTheLevel() {
        assertEquals(Optional.of(Skill.UNTRAINED), Skill.fromStrainsPerPick(2));
        assertEquals(Optional.of(Skill.BASIC), Skill.fromStrainsPerPick(4));
        assertEquals(Optional.of(Skill.MASTER), Skill.fromStrainsPerPick(6));
    }

    /**
     * A pick can arrive already worn from an earlier lock, so it can break after any number of
     * strains. That is not a level, and the answer must not be guessed into one.
     */
    @Test
    void anUnrecognisedStrainCountNamesNoLevel() {
        assertEquals(Optional.empty(), Skill.fromStrainsPerPick(1));
        assertEquals(Optional.empty(), Skill.fromStrainsPerPick(3));
        assertEquals(Optional.empty(), Skill.fromStrainsPerPick(5));
        assertEquals(Optional.empty(), Skill.fromStrainsPerPick(0));
    }
}
