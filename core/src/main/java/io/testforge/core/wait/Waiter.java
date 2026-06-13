package io.testforge.core.wait;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.awaitility.Awaitility;

/**
 * The single entry point for waiting on asynchronous effects (DB rows, Kafka
 * events, state transitions). Never use Thread.sleep in tests — a fixed sleep
 * is either too short (flaky) or too long (slow); polling with a deadline is
 * both faster and more stable.
 */
public class Waiter {

    private final WaitProperties properties;

    public Waiter(WaitProperties properties) {
        this.properties = properties;
    }

    /**
     * Polls {@code supplier} until its value satisfies {@code until},
     * then returns the last value. Exceptions thrown while polling are
     * treated as "not ready yet".
     *
     * <p>Polling happens on the calling thread ({@code pollInSameThread}):
     * no extra poller thread per await — cheap on virtual threads — and the
     * condition sees the caller's thread-bound context (for example,
     * {@code ScenarioContext}). Trade-off: a
     * condition that blocks forever cannot be interrupted by the timeout, so
     * keep conditions to quick queries.
     */
    public <T> T await(String description, Supplier<T> supplier, Predicate<T> until) {
        AtomicReference<T> last = new AtomicReference<>();

        Awaitility.await(description)
                .atMost(properties.timeout())
                .pollInterval(properties.pollInterval())
                .pollInSameThread()
                .ignoreExceptions()
                .until(() -> {
                    T value = supplier.get();
                    last.set(value);
                    return value != null && until.test(value);
                });

        return last.get();
    }

    public void awaitTrue(String description, Callable<Boolean> condition) {
        Awaitility.await(description)
                .atMost(properties.timeout())
                .pollInterval(properties.pollInterval())
                .pollInSameThread()
                .ignoreExceptions()
                .until(condition);
    }
}
