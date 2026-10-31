package io.testforge.data.isolation;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Tags that keep data of parallel tests and parallel runs apart on a shared
 * environment. Put a tag into a value the test sends anyway — a name, an email,
 * a reference — and filter what the environment returns by it:
 *
 * <pre>{@code
 * String email = DataIsolation.tag("anna") + "@example.test";  // tf-k3x9q-t4-anna@example.test
 * List<String> mine = allOrderReferences.stream().filter(DataIsolation::owns).toList();
 * }</pre>
 *
 * The run tag ({@code <prefix>-<run>}) is fixed for the JVM: generated once, or
 * taken from {@code forge.data.isolation.run-tag} (a CI run id, say). Inside a
 * test run with {@link DataIsolationExtension} the scope tag adds a per-test
 * part ({@code -t4}), so two tests of the same run never match each other's
 * data either. A sweeper job recognizes any run's data with {@link #isTestData}.
 */
public final class DataIsolation {

    public static final String DEFAULT_PREFIX = "tf";

    private static final Pattern TAG_PART = Pattern.compile("[A-Za-z0-9]+");
    private static final String GENERATED_RUN = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    private static final ThreadLocal<String> TEST = new ThreadLocal<>();
    private static final AtomicLong TESTS = new AtomicLong();

    private static volatile String prefix = DEFAULT_PREFIX;
    private static volatile String run = GENERATED_RUN;

    private DataIsolation() {
    }

    /**
     * Sets the prefix and run id, normally from {@code forge.data.isolation}.
     * A blank run id keeps the one generated for this JVM.
     */
    public static void configure(String newPrefix, String newRun) {
        String effectivePrefix = newPrefix == null || newPrefix.isBlank() ? DEFAULT_PREFIX : newPrefix;
        String effectiveRun = newRun == null || newRun.isBlank() ? GENERATED_RUN : newRun;
        requireTagPart("prefix", effectivePrefix);
        requireTagPart("run-tag", effectiveRun);
        prefix = effectivePrefix.toLowerCase(Locale.ROOT);
        run = effectiveRun.toLowerCase(Locale.ROOT);
    }

    /** The tag shared by every test of this run, e.g. {@code tf-k3x9q}. */
    public static String runTag() {
        return prefix + "-" + run;
    }

    /** The current test's tag ({@code tf-k3x9q-t4}), or the run tag outside one. */
    public static String scopeTag() {
        String test = TEST.get();
        return test == null ? runTag() : runTag() + "-" + test;
    }

    /** The value prefixed with the scope tag: {@code tf-k3x9q-t4-anna}. */
    public static String tag(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value must not be blank");
        }
        return scopeTag() + "-" + value;
    }

    /** Whether the value carries the current scope's tag: this test's data, or this run's outside a test. */
    public static boolean owns(String value) {
        return carries(value, scopeTag());
    }

    /** Whether the value was tagged by any test of this run. */
    public static boolean ownedByRun(String value) {
        return carries(value, runTag());
    }

    /** Whether the value was tagged by any run with this prefix: what a sweeper job may remove. */
    public static boolean isTestData(String value) {
        return value != null && Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(prefix) + "-[a-z0-9]+")
                .matcher(value.toLowerCase(Locale.ROOT)).find();
    }

    /** The per-test part of the current scope ({@code t4}), if a test is running. */
    public static Optional<String> testTag() {
        return Optional.ofNullable(TEST.get());
    }

    static String openTest() {
        String test = "t" + TESTS.incrementAndGet();
        TEST.set(test);
        return test;
    }

    static void closeTest() {
        TEST.remove();
    }

    private static boolean carries(String value, String tag) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        int at = lower.indexOf(tag);
        while (at >= 0) {
            int end = at + tag.length();
            boolean startsCleanly = at == 0 || !Character.isLetterOrDigit(lower.charAt(at - 1));
            boolean endsCleanly = end == lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (startsCleanly && endsCleanly) {
                return true;
            }
            at = lower.indexOf(tag, at + 1);
        }
        return false;
    }

    private static void requireTagPart(String name, String value) {
        if (!TAG_PART.matcher(value).matches()) {
            throw new IllegalArgumentException("forge.data.isolation." + name
                    + " must be letters and digits only, got '" + value + "'");
        }
    }
}
