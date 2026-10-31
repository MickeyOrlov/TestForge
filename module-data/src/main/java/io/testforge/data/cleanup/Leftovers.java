package io.testforge.data.cleanup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What cleanup could not undo in this JVM: recorded by {@link CleanupExtension},
 * published to the run index by {@link LeftoverReporter} as
 * {@code module-data/leftovers.json}, so a sweeper or a person can remove it.
 */
public final class Leftovers {

    private static final int MAX_ERROR_CHARS = 500;
    private static final List<Leftover> RECORDED = new CopyOnWriteArrayList<>();

    /**
     * @param test        the test (or class, for {@code @BeforeAll} data) that created it
     * @param description what was registered, e.g. {@code order o-1}
     * @param error       first line of what the undo threw
     * @param scopeTag    the isolation tag the data carries, if the test had one
     * @param testFailed  whether the test itself failed too
     * @param at          when the undo failed
     */
    public record Leftover(String test, String description, String error, String scopeTag, boolean testFailed,
                           Instant at) {
    }

    private Leftovers() {
    }

    /** Everything recorded and not yet published, oldest first. */
    public static List<Leftover> recorded() {
        return List.copyOf(RECORDED);
    }

    static void record(String test, Cleanup.Failure failure, String scopeTag, boolean testFailed) {
        RECORDED.add(new Leftover(test, failure.description(), firstLine(failure.cause()), scopeTag, testFailed,
                Instant.now()));
    }

    /** Takes everything recorded so far; a later call returns only what came after. */
    static List<Leftover> drain() {
        List<Leftover> drained = new ArrayList<>();
        for (Leftover leftover : RECORDED) {
            if (RECORDED.remove(leftover)) {
                drained.add(leftover);
            }
        }
        return drained;
    }

    private static String firstLine(Throwable cause) {
        String text = cause.toString().lines().findFirst().orElse(cause.getClass().getName());
        return text.length() > MAX_ERROR_CHARS ? text.substring(0, MAX_ERROR_CHARS) + "…" : text;
    }
}
