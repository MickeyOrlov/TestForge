package io.testforge.core.wait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class WaiterTest {

    private final Waiter waiter = new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20)));

    record Job(String id, String status) {
    }

    @Test
    void returnsTheValueThatSatisfiedTheCondition() {
        AtomicInteger polls = new AtomicInteger();

        Job job = waiter.await("job completes",
                () -> new Job("j1", polls.incrementAndGet() < 3 ? "Running" : "Completed"),
                done -> "Completed".equals(done.status()));

        assertThat(job.status()).isEqualTo("Completed");
    }

    @Test
    void aTimeoutSaysWhatTheLastPollSaw() {
        assertThatThrownBy(() -> waiter.await("job j1 becomes Completed",
                () -> new Job("j1", "Failed"), job -> "Completed".equals(job.status())))
                .isInstanceOf(WaitTimeoutException.class)
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageStartingWith("Waited PT0.3S for job j1 becomes Completed (")
                .hasMessageContaining("; last value: Job[id=j1, status=Failed]")
                .hasMessageNotContaining("last error");
    }

    @Test
    void aTimeoutSaysWhyEveryPollFailed() {
        WaitTimeoutException timeout = (WaitTimeoutException) org.assertj.core.api.Assertions.catchThrowable(() ->
                waiter.await("order o1 is visible", () -> {
                    throw new IllegalStateException("GET /orders/o1 -> 503 Service Unavailable\n<html>...</html>");
                }, order -> true));

        assertThat(timeout.getMessage())
                .contains("; no poll produced a value")
                .endsWith("; last error: IllegalStateException: GET /orders/o1 -> 503 Service Unavailable");
        assertThat(timeout.lastError()).isInstanceOf(IllegalStateException.class);
        assertThat(timeout.polls()).isGreaterThan(1);
    }

    @Test
    void anErrorThatWentAwayIsStillReportedIfTheWaitTimesOut() {
        AtomicInteger polls = new AtomicInteger();

        assertThatThrownBy(() -> waiter.awaitTrue("flag set", () -> {
            if (polls.incrementAndGet() == 1) {
                throw new java.io.IOException("connection reset");
            }
            return false;
        }))
                .isInstanceOf(WaitTimeoutException.class)
                .hasMessageContaining("; last value: false")
                .hasMessageContaining("; last error: IOException: connection reset");
    }

    @Test
    void aHugeValueIsCutInTheMessage() {
        String huge = "x".repeat(Waiter.MAX_VALUE_CHARS * 3);

        assertThatThrownBy(() -> waiter.await("never", () -> huge, value -> false))
                .satisfies(e -> assertThat(e.getMessage().length()).isLessThan(Waiter.MAX_VALUE_CHARS + 200))
                .hasMessageEndingWith("...");
    }

    @Test
    void aTerminalStateStopsTheWaitAtOnce() {
        AtomicInteger polls = new AtomicInteger();
        Waiter patient = waiter.withTimeout(Duration.ofSeconds(30));

        long started = System.nanoTime();
        WaitAbortedException aborted = (WaitAbortedException) org.assertj.core.api.Assertions.catchThrowable(() ->
                patient.await("job j1 completes",
                        () -> new Job("j1", polls.incrementAndGet() < 3 ? "Running" : "Failed"),
                        job -> "Completed".equals(job.status()),
                        job -> "Failed".equals(job.status())));

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        assertThat(aborted).isInstanceOf(AssertionError.class)
                .hasMessage("Stopped waiting for job j1 completes after 3 polls: Job[id=j1, status=Failed] "
                        + "is a state it will not leave");
        assertThat(aborted.value()).isEqualTo(new Job("j1", "Failed"));
    }

    @Test
    void withoutATerminalStateTheFailFastWaitBehavesLikeAPlainOne() {
        AtomicInteger polls = new AtomicInteger();

        Job job = waiter.await("job completes",
                () -> new Job("j1", polls.incrementAndGet() < 3 ? "Running" : "Completed"),
                done -> "Completed".equals(done.status()),
                failed -> "Failed".equals(failed.status()));

        assertThat(job.status()).isEqualTo("Completed");
        assertThatThrownBy(() -> waiter.await("stays running", () -> new Job("j2", "Running"),
                done -> false, failed -> false)).isInstanceOf(WaitTimeoutException.class);
    }

    @Test
    void oneWaitCanHaveItsOwnDeadline() {
        Waiter quick = new Waiter(new WaitProperties(Duration.ofSeconds(30), Duration.ofMillis(500)))
                .withTimeout(Duration.ofMillis(100));

        assertThat(quick.properties().timeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(quick.properties().pollInterval()).as("the configured interval is kept")
                .isEqualTo(Duration.ofMillis(500));
        assertThat(quick.withPollInterval(Duration.ofMillis(10)).properties().pollInterval())
                .isEqualTo(Duration.ofMillis(10));
        assertThatThrownBy(() -> quick.await("never", () -> "x", value -> false))
                .hasMessageStartingWith("Waited PT0.1S for never");
        assertThatThrownBy(() -> quick.withTimeout(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
