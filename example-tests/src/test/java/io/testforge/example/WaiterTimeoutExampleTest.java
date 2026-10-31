package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.core.wait.WaitTimeoutException;
import io.testforge.core.wait.Waiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What a wait that runs out of time says: the last value it saw, so a job that
 * went {@code Failed} is not mistaken for one still {@code Running}, and the
 * last error a poll threw, so a backend answering 503 on every poll is not
 * mistaken for a slow one.
 */
@SpringBootTest(properties = {"forge.wait.timeout=300ms", "forge.wait.poll-interval=50ms"})
class WaiterTimeoutExampleTest {

    record Job(String id, String status) {
    }

    @Autowired
    Waiter waiter;

    @Test
    void aTimeoutNamesTheLastValueSeen() {
        assertThatThrownBy(() -> waiter.await("job j-7 becomes Completed",
                () -> new Job("j-7", "Failed"), job -> "Completed".equals(job.status())))
                .isInstanceOf(WaitTimeoutException.class)
                .hasMessageContaining("last value: Job[id=j-7, status=Failed]");
    }

    @Test
    void aTimeoutNamesTheErrorEveryPollHit() {
        assertThatThrownBy(() -> waiter.await("order o-1 is visible", () -> {
            throw new IllegalStateException("GET /orders/o-1 -> 503");
        }, order -> true))
                .isInstanceOf(WaitTimeoutException.class)
                .hasMessageContaining("last error: IllegalStateException: GET /orders/o-1 -> 503");
    }

    @Test
    void aStateTheJobWillNotLeaveStopsTheWaitAtOnce() {
        // 30 s of patience, but a Failed job will never complete: the test fails now, with the job
        assertThatThrownBy(() -> waiter.withTimeout(java.time.Duration.ofSeconds(30)).await("job j-9 completes",
                () -> new Job("j-9", "Failed"),
                job -> "Completed".equals(job.status()),
                job -> "Failed".equals(job.status()) || "Cancelled".equals(job.status())))
                .isInstanceOf(io.testforge.core.wait.WaitAbortedException.class)
                .hasMessageContaining("Job[id=j-9, status=Failed] is a state it will not leave");
    }
}
