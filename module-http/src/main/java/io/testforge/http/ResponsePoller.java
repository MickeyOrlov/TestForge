package io.testforge.http;

import io.restassured.response.Response;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.awaitility.core.ConditionTimeoutException;

/**
 * Repeats a call until its response matches — the replacement for the sleep
 * loops asynchronous APIs otherwise attract:
 *
 * <pre>{@code
 * Response done = poll.until(() -> api.request().get("/jobs/{id}", id), jsonPath("status", "DONE"));
 * }</pre>
 *
 * Polls through {@link Waiter} ({@code forge.wait} timeout and interval unless
 * overridden per call). A call that throws counts as "not yet". On timeout it
 * fails with the condition, the number of attempts and the last response —
 * status and a redacted, shortened body — instead of a bare timeout.
 */
public class ResponsePoller {

    private static final int BODY_EXCERPT = 500;

    private final WaitProperties defaults;
    private final Redactor redactor;

    public ResponsePoller(WaitProperties defaults, Redactor redactor) {
        this.defaults = defaults;
        this.redactor = redactor;
    }

    public Response until(Supplier<Response> call, Predicate<Response> condition) {
        return until(call, condition, defaults.timeout(), defaults.pollInterval());
    }

    public Response until(Supplier<Response> call, Predicate<Response> condition, Duration timeout) {
        return until(call, condition, timeout, defaults.pollInterval());
    }

    public Response until(Supplier<Response> call, Predicate<Response> condition, Duration timeout,
                          Duration pollInterval) {
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(condition, "condition");
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<Response> last = new AtomicReference<>();
        AtomicReference<RuntimeException> lastError = new AtomicReference<>();
        try {
            return new Waiter(new WaitProperties(timeout, pollInterval)).await("response to match " + condition,
                    () -> {
                        attempts.incrementAndGet();
                        try {
                            Response response = call.get();
                            last.set(response);
                            return response;
                        } catch (RuntimeException e) {
                            lastError.set(e);
                            throw e;
                        }
                    },
                    condition);
        } catch (ConditionTimeoutException timeoutException) {
            throw new AssertionError(describe(condition, timeout, attempts.get(), last.get(), lastError.get()),
                    timeoutException);
        }
    }

    private String describe(Predicate<Response> condition, Duration timeout, int attempts, Response last,
                            RuntimeException lastError) {
        StringBuilder message = new StringBuilder("Response did not match ").append(condition)
                .append(" within ").append(timeout).append(" (").append(attempts).append(" attempts)");
        if (last != null) {
            String body = redactor.body(last.asString());
            if (body != null && body.length() > BODY_EXCERPT) {
                body = body.substring(0, BODY_EXCERPT) + "...";
            }
            message.append("; last response: status ").append(last.getStatusCode())
                    .append(", body ").append(body == null || body.isBlank() ? "<empty>" : body);
        }
        if (lastError != null) {
            message.append("; last call error: ").append(lastError);
        }
        return message.toString();
    }
}
