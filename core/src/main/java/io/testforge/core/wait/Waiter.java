package io.testforge.core.wait;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;

/**
 * The single entry point for waiting on asynchronous effects (DB rows, Kafka
 * events, state transitions). Never use Thread.sleep in tests — a fixed sleep
 * is either too short (flaky) or too long (slow); polling with a deadline is
 * both faster and more stable.
 *
 * <p>A wait that times out throws {@link WaitTimeoutException} naming the last
 * value it saw and the last error a poll threw.
 */
public class Waiter {

    static final int MAX_VALUE_CHARS = 500;

    private final WaitProperties properties;

    public Waiter(WaitProperties properties) {
        this.properties = properties;
    }

    /**
     * Polls {@code supplier} until its value satisfies {@code until},
     * then returns the last value. Exceptions thrown while polling are
     * treated as "not ready yet" — and the last one is reported if the wait
     * times out.
     *
     * <p>Polling happens on the calling thread ({@code pollInSameThread}):
     * no extra poller thread per await — cheap on virtual threads — and the
     * condition sees the caller's thread-bound context (for example,
     * {@code ScenarioContext}). Trade-off: a
     * condition that blocks forever cannot be interrupted by the timeout, so
     * keep conditions to quick queries.
     */
    public <T> T await(String description, Supplier<T> supplier, Predicate<T> until) {
        Observed<T> observed = new Observed<>();
        poll(description, observed, () -> {
            T value = supplier.get();
            observed.value(value);
            return value != null && until.test(value);
        });
        return observed.value.get();
    }

    public void awaitTrue(String description, Callable<Boolean> condition) {
        Observed<Boolean> observed = new Observed<>();
        poll(description, observed, () -> {
            Boolean value = condition.call();
            observed.value(value);
            return Boolean.TRUE.equals(value);
        });
    }

    private void poll(String description, Observed<?> observed, Callable<Boolean> condition) {
        try {
            Awaitility.await(description)
                    .atMost(properties.timeout())
                    .pollInterval(properties.pollInterval())
                    .pollInSameThread()
                    .until(() -> {
                        observed.polls.incrementAndGet();
                        try {
                            return condition.call();
                        } catch (Exception e) {
                            // not ready yet; kept so a timeout can say why every poll failed
                            observed.error.set(e);
                            return false;
                        }
                    });
        } catch (ConditionTimeoutException timeout) {
            throw new WaitTimeoutException(message(description, observed), observed.value.get(),
                    observed.error.get(), observed.polls.get(), timeout);
        }
    }

    private String message(String description, Observed<?> observed) {
        StringBuilder out = new StringBuilder("Waited ").append(properties.timeout())
                .append(" for ").append(description)
                .append(" (").append(observed.polls.get()).append(observed.polls.get() == 1 ? " poll" : " polls")
                .append(")");
        if (observed.seen) {
            out.append("; last value: ").append(shorten(String.valueOf(observed.value.get())));
        } else {
            out.append("; no poll produced a value");
        }
        Throwable error = observed.error.get();
        if (error != null) {
            out.append("; last error: ").append(error.getClass().getSimpleName());
            if (error.getMessage() != null) {
                out.append(": ").append(shorten(error.getMessage().strip().lines().findFirst().orElse("")));
            }
        }
        return out.toString();
    }

    private static String shorten(String text) {
        return text.length() > MAX_VALUE_CHARS ? text.substring(0, MAX_VALUE_CHARS) + "..." : text;
    }

    private static final class Observed<T> {
        final AtomicReference<T> value = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicInteger polls = new AtomicInteger();
        volatile boolean seen;

        void value(T observed) {
            value.set(observed);
            seen = true;
        }
    }
}
