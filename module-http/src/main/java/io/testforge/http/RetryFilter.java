package io.testforge.http;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.testforge.core.wait.Waiter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.core.ConditionTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Retries requests that failed for infrastructure reasons — a gateway
 * restarting, a load balancer with no healthy upstream yet — and nothing else.
 *
 * <p>Two guardrails keep this from hiding real defects: it is disabled unless
 * a profile turns it on, and it only repeats methods that are safe to repeat
 * ({@code forge.http.retry.methods}). A retried request is always logged at
 * WARN, so a suite that is quietly leaning on retries is visible in CI output
 * instead of just looking slow.
 *
 * <p>Waiting between attempts goes through {@link Waiter} like every other
 * wait in the framework — the first attempt is immediate, the retries are
 * spaced by {@code forge.http.retry.delay} and bounded by
 * {@code forge.http.retry.timeout}. When the attempts run out, the last
 * response is returned rather than an exception: the test's own assertion on
 * the status code is the better error message.
 *
 * <p>Runs innermost so retries repeat the fully prepared request. Filters
 * registered after this one are applied on the first attempt only.
 */
public class RetryFilter implements OrderedFilter {

    private static final Logger log = LoggerFactory.getLogger("forge.http");

    private final Waiter waiter;
    private final HttpProperties.RetryProperties properties;

    public RetryFilter(Waiter waiter, HttpProperties.RetryProperties properties) {
        this.waiter = waiter;
        this.properties = properties;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        String method = requestSpec.getMethod();
        if (!properties.methods().contains(method.toUpperCase(Locale.ROOT))) {
            return ctx.next(requestSpec, responseSpec);
        }

        AtomicReference<Response> last = new AtomicReference<>();
        RuntimeException firstFailure = null;
        try {
            Response first = ctx.next(requestSpec, responseSpec);
            last.set(first);
            if (!retryable(first)) {
                return first;
            }
        } catch (RuntimeException e) {
            firstFailure = e;
        }

        AtomicInteger retries = new AtomicInteger();
        String description = "%s %s to stop failing".formatted(method, requestSpec.getURI());
        try {
            waiter.await(description,
                    () -> {
                        logRetry(method, requestSpec.getURI(), last.get(), retries.incrementAndGet());
                        Response response = ctx.send(requestSpec);
                        last.set(response);
                        return response;
                    },
                    response -> !retryable(response));
        } catch (ConditionTimeoutException timeout) {
            if (last.get() == null) {
                throw firstFailure == null ? timeout : firstFailure;
            }
            log.warn("{} {} still {} after {} retries", method, requestSpec.getURI(),
                    last.get().getStatusCode(), retries.get());
        }

        return last.get();
    }

    private void logRetry(String method, String uri, Response previous, int attempt) {
        log.warn("Retry {} of {} {} after {}", attempt, method, uri,
                previous == null ? "a transport failure" : "status " + previous.getStatusCode());
    }

    private boolean retryable(Response response) {
        return properties.statuses().contains(response.getStatusCode());
    }

    @Override
    public int getOrder() {
        return OrderedFilter.LOWEST_PRECEDENCE;
    }
}
