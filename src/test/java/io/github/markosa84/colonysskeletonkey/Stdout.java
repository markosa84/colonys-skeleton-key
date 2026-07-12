package io.github.markosa84.colonysskeletonkey;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Captures what a routine prints. The tool reports to the console - what a run learned, what a
 * broken pick said about the character, why an F8 was ignored - and that report is the only thing
 * the player ever sees, so it is worth asserting on.
 */
public final class Stdout {

    private Stdout() {}

    /** Runs {@code body} with {@code System.out} redirected, and returns everything it printed. */
    public static String capturing(Runnable body) {
        PrintStream real = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            body.run();
        } finally {
            System.setOut(real);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    /** For a body worth calling for its result: returns it, with whatever it printed swallowed. */
    public static <T> T quietly(Supplier<T> body) {
        List<T> result = new ArrayList<>(1);
        capturing(() -> result.add(body.get()));
        return result.getFirst();
    }
}
