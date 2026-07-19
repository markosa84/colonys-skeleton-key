package io.github.markosa84.colonysskeletonkey.session;

import java.util.Arrays;
import java.util.List;

import io.github.markosa84.colonysskeletonkey.solver.Connection;
import io.github.markosa84.colonysskeletonkey.solver.LockModel;

/**
 * Every line {@link LockSession} says to the player, in one place. The session decides <i>what</i>
 * happened; this decides how to phrase it, so the two concerns stop being tangled and the session's
 * control flow reads as decisions rather than prose.
 *
 * <p>It is deliberately stateless and prints to {@link System#out} <b>at call time</b>: a run is
 * teed to its log file by swapping {@code System.out} for the run's duration
 * ({@code AutoLockpick.solveLogged}), and the tests capture the same stream, so resolving it lazily
 * is what keeps both working. The verbose, file-only move-by-move trace is a separate channel and
 * stays on {@code LockSession} ({@code traceTo}); this is only the console story.
 *
 * <p>The strings here are load-bearing: {@code LockSessionTest} asserts on substrings of them, so a
 * reword is a test change. That is the point of gathering them - a message lives in exactly one spot.
 */
final class SessionReporter {

    // --- run() ---

    void noLockDetected() {
        System.out.println("No " + LockModel.MIN_PLATES + "-" + LockModel.MAX_PLATES + " plate "
                + "lock detected. Is the lock open, with the game in the foreground?");
        System.out.println("If the lock was open and the saved frame looks fine, the tool is "
                + "reading the wrong part of it: open the .txt beside it, and please report it.");
    }

    void detected(int n, int[] cur) {
        System.out.println("Detected " + n + " plates at " + Arrays.toString(cur)
                + ". Learning the connections, then solving.");
    }

    void solved(int strains, int picksBroken, int strainsPerPick, boolean everReset) {
        System.out.println("Lock solved. " + strains + " strain(s), " + picksBroken
                + " pick(s) broken." + skillSeen(picksBroken, strainsPerPick, everReset));
    }

    void unreadable(String when) {
        System.out.println("Could not read the lock " + when + ". Is something covering it (a tooltip, "
                + "the mouse cursor, a notification), or did the game move on?");
    }

    // --- loop() give-ups ---

    void stuckHiddenRow() {
        System.out.println("Stuck: a plate's hole row stays hidden after every nudge. "
                + "Slide any plate one step by hand and press F8 again.");
    }

    void giveUpPicksSpent(int strains, int picksSpent, List<Integer> unprobed) {
        System.out.println("Giving up after " + strains + " strain(s), about " + picksSpent
                + " broken pick(s). Plates " + unprobed + " never moved. Every lock in the "
                + "game opens well inside this, so the fault is here, not in the lock.");
    }

    void misreadGiveUp(int misreadStrains) {
        System.out.println("Stopping: " + misreadStrains + " slides I read as safe still "
                + "strained, which can only be a misread offset. This frame is unusually dark; "
                + "the reader has lost it, so going on would only cost picks. Saved the frame.");
        pleaseReportSavedFiles();
    }

    void noProgress(int misreadStrains) {
        System.out.println("Stopping: I keep returning to the same configuration without "
                + "learning anything new" + (misreadStrains > 0
                    ? " - most likely a misread on this unusually dark frame." : ".")
                + " Saved the frame instead of looping.");
    }

    void wrongModel() {
        System.out.println("Nothing moved. No plate would slide in either direction, and "
                + "a lock the game can open always has a legal move - so the lock I read "
                + "is not the lock on screen (the plate count or the offsets are wrong). "
                + "That is a bug in this tool, not a hard lock.");
        pleaseReportSavedFiles();
    }

    void unsolvableModelGiveUp() {
        System.out.println("The connections I learned do not add up to a lock that opens, "
                + "but the game only ever gives you locks that do - so I misread a plate "
                + "while learning it, and re-probing could not correct it. This frame is "
                + "dark. Saved it.");
        pleaseReportSavedFiles();
    }

    void stuck(List<Integer> unprobed, int misreadStrains) {
        System.out.println("Stuck: no move is left to try. Plates " + unprobed
                + " will not budge in either direction, and no probed plate can free them."
                + (misreadStrains > 0 ? " (" + misreadStrains + " slide(s) I read as safe still "
                    + "strained on the way - this frame is dark and I may be misreading it.)"
                    : ""));
    }

    private void pleaseReportSavedFiles() {
        System.out.println("Please report the saved .png and the .txt beside it.");
    }

    // --- step() ---

    void pickBroke(int strainsPerPick, int observedBreaks, boolean resetThisBreak, boolean everReset,
            int[] cur) {
        System.out.println("  the pick broke after " + strainsPerPick + " strain(s)"
                + levelSeen(strainsPerPick, everReset)
                + " (" + observedBreaks + " so far)"
                + (resetThisBreak
                    ? "; the puzzle reset to " + Arrays.toString(cur)
                    : "; the lock is untouched at " + Arrays.toString(cur))
                + ". Keeping every connection learned so far.");
    }

    void refusedLegalMove(int p) {
        System.out.println("  plate " + p + " refused a move its connections say is legal;"
                + " discarding what we knew about it.");
    }

    void contradictoryStrain(int p, int dir, int misreadStrains) {
        System.out.println("  plate " + p + " would not slide " + name(dir) + " though nothing sits "
                + "at an end - I am misreading a plate on this dark frame (" + misreadStrains
                + " so far).");
    }

    void willNotSlideYet(int p, int dir, int strains) {
        System.out.println("  plate " + p + " will not slide " + name(dir) + " yet: it must drag a "
                + "plate already at the end of its track (strain " + strains + ").");
    }

    // --- learn() / recovery / hidden rows / discovery ---

    void probed(int p, Connection[] row, boolean allProbed) {
        System.out.println("  plate " + p + " drags " + describe(row)
                + (allProbed ? "  -- every connection known, solving" : ""));
    }

    void reProbing(int suspect) {
        System.out.println("  the learned model will not open the lock, which can only be a misread "
                + "while probing. Re-probing plate " + suspect + " to correct it.");
    }

    void fillingHiddenRow() {
        System.out.println("  a hole row is hidden; filling it in from the model.");
    }

    void hiddenRowDiscardKnown(int p) {
        System.out.println("  plate " + p + " did not do what its connections predicted, and a"
                + " hidden row prevents relearning here; discarding what we knew about it.");
    }

    void undoingHidRow(int p) {
        System.out.println("  plate " + p + "'s move hid another plate's row; undoing it to"
                + " probe again from a different configuration.");
    }

    void nudgingHiddenRow(int plate) {
        System.out.println("  a hole row is hidden; nudging plate " + plate
                + " to uncover it.");
    }

    void noSolution(int[] cur) {
        System.out.println("No strain-free solution exists from " + Arrays.toString(cur) + ".");
    }

    void clearingEnds(int p, int moveCount) {
        System.out.println("  plate " + p + " is blocked; clearing the ends for it with "
                + moveCount + " move(s) of plates we already understand.");
    }

    void repositioning(int moveCount) {
        System.out.println("  no safe probe left; repositioning plates we understand to free one to "
                + "gamble (" + moveCount + " move(s)).");
    }

    // --- shared formatters ---

    /** The tail of the report line, when a pick broke and so said something about the character. */
    private String skillSeen(int observedBreaks, int strainsPerPick, boolean everReset) {
        if (observedBreaks == 0) {
            return "";
        }
        return " The last pick took " + strainsPerPick + " strain(s)"
                + levelSeen(strainsPerPick, everReset) + ".";
    }

    /**
     * The lockpicking level the last broken pick revealed, as a clause to append - or nothing.
     *
     * <p>Nothing depends on this; it is for the player to read. And it is deliberately hedged: a
     * pick carries its damage between locks, so the first one a run breaks may have arrived already
     * worn and break early. A strain count that matches no level is reported as exactly that rather
     * than rounded into a guess. The one thing that is certain either way is the reset: only an
     * untrained character's break sends every plate home.
     */
    private String levelSeen(int strainsPerPick, boolean everReset) {
        return Skill.fromStrainsPerPick(strainsPerPick)
                .map(s -> " - that is " + s.name().toLowerCase() + " lockpicking")
                .orElse(everReset
                        ? " - the puzzle reset, so the character is untrained"
                        : " - no level breaks on that many, so that pick came in already worn");
    }

    private static String name(int dir) {
        return dir > 0 ? "left" : "right";
    }

    /** Human-readable connection row - shared with {@code LockSession}'s file-only model trace. */
    static String describe(Connection[] row) {
        if (row.length == 0) return "nothing";
        StringBuilder sb = new StringBuilder();
        for (int j = 0; j < row.length; j++) {
            if (j > 0) sb.append(", ");
            sb.append("plate ").append(row[j].target())
              .append(row[j].type() == Connection.Type.NORMAL ? " (normal)" : " (inverted)");
        }
        return sb.toString();
    }
}
