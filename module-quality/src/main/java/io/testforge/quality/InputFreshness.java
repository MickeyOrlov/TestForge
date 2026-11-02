package io.testforge.quality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Whether another tool's report belongs to this build. JUnit suites carry a timestamp; PIT, diff-cover
 * and WFC reports do not, so their file time is compared with {@code forge.quality.results-not-before}:
 * a report from an earlier build would otherwise be judged as this one.
 */
final class InputFreshness {

    private InputFreshness() {
    }

    /** Why {@code file} is stale, or {@code null} when it is fresh or there is no limit. */
    static String stale(Path file, Instant notBefore) {
        if (notBefore == null) {
            return null;
        }
        try {
            Instant written = Files.getLastModifiedTime(file).toInstant();
            return written.isBefore(notBefore) ? file + " was written " + written + ", before this build ("
                    + "results-not-before " + notBefore + "): a report of an earlier build" : null;
        } catch (IOException e) {
            return "cannot read the time of " + file;
        }
    }

    /** {@code file} as the report's inputs list it: with its time, or that it is missing. */
    static String describe(Path file) {
        try {
            return file + " (written " + Files.getLastModifiedTime(file).toInstant() + ")";
        } catch (IOException e) {
            return file + " (missing)";
        }
    }
}
