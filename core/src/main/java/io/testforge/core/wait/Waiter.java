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

    /**
     * As {@link #await(String, Supplier, Predicate)}, but stops at once when a
     * polled value satisfies {@code failWhen} — a terminal state the value will
     * not leave, such as a job that went {@code Failed} while the test waits for
     * {@code Completed}. Waiting out the deadline there only delays the same
     * failure; this throws {@link WaitAbortedException} with the value instead.
     *
     * <pre>{@code
     * waiter.await("job completes", () -> jobs.get(id),
     *         job -> job.status() == COMPLETED,
     *         job -> job.status() == FAILED || job.status() == CANCELLED);
     * }</pre>
     */
    public <T> T await(String description, Supplier<T> supplier, Predicate<T> until, Predicate<T> failWhen) {
        Observed<T> observed = new Observed<>();
        AtomicReference<T> terminal = new AtomicReference<>();
        poll(description, observed, () -> {
            T value = supplier.get();
            observed.value(value);
            if (value != null && failWhen.test(value)) {
                terminal.set(value);
                return true;
            }
            return value != null && until.test(value);
        });
        if (terminal.get() != null) {
            throw new WaitAbortedException("Stopped waiting for " + description + " after "
                    + observed.polls.get() + (observed.polls.get() == 1 ? " poll" : " polls")
                    + ": " + shorten(String.valueOf(terminal.get())) + " is a state it will not leave",
                    terminal.get(), observed.polls.get());
        }
        return observed.value.get();
    }

    /**
     * A waiter with another deadline for the waits that need one — a report
     * that takes minutes to generate, an event that must arrive within two
     * seconds — keeping this waiter's poll interval (polls are never spaced
     * wider than half the deadline).
     */
    public Waiter withTimeout(java.time.Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("A wait timeout must be positive, got " + timeout);
        }
        return new Waiter(new WaitProperties(timeout, properties.pollInterval()));
    }

    /** A waiter that polls at another interval, keeping this waiter's deadline. */
    public Waiter withPollInterval(java.time.Duration pollInterval) {
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("A poll interval must be positive, got " + pollInterval);
        }
        return new Waiter(new WaitProperties(properties.timeout(), pollInterval));
    }

    /** The deadline and poll interval this waiter uses. */
    public WaitProperties properties() {
        return properties;
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
                    .pollInterval(effectivePollInterval())
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

    /**
     * The configured interval, never more than half the deadline: Awaitility
     * refuses an interval as long as the timeout, and a single poll per wait
     * would be no wait at all.
     */
    private java.time.Duration effectivePollInterval() {
        java.time.Duration half = properties.timeout().dividedBy(2);
        java.time.Duration interval = properties.pollInterval().compareTo(half) > 0 ? half : properties.pollInterval();
        return interval.isZero() ? java.time.Duration.ofMillis(1) : interval;
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
