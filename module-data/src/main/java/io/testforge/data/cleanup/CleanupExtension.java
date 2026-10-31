package io.testforge.data.cleanup;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@link Cleanup} registrations: after each test for what the test
 * registered, after all tests for what {@code @BeforeAll} registered.
 *
 * <pre>{@code
 * @ExtendWith(CleanupExtension.class)
 * class OrdersTest { ... }
 * }</pre>
 *
 * A cleanup problem never hides the test's own failure: it is attached to it
 * as suppressed. When the test passed, leftover data is a finding, so the test
 * fails with every action that could not be undone.
 */
public class CleanupExtension implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback,
        AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(CleanupExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        Cleanup.openScope();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        Cleanup.openScope();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        report(context, Cleanup.closeScope());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        report(context, Cleanup.closeScope());
    }

    private static void report(ExtensionContext context, List<Cleanup.Failure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        String summary = "Cleanup after " + context.getDisplayName() + " left data behind:\n" + failures.stream()
                .map(failure -> "  - " + failure.description() + ": " + failure.cause())
                .collect(Collectors.joining("\n"));
        if (context.getExecutionException().isPresent()) {
            Throwable testFailure = context.getExecutionException().get();
            failures.forEach(failure -> testFailure.addSuppressed(
                    new IllegalStateException("cleanup failed: " + failure.description(), failure.cause())));
            log.warn(summary);
            return;
        }
        AssertionError error = new AssertionError(summary);
        failures.forEach(failure -> error.addSuppressed(failure.cause()));
        throw error;
    }
}
