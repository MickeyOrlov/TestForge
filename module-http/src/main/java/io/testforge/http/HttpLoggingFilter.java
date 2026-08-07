package io.testforge.http;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes every request to the {@code forge.http} logger — the HTTP counterpart
 * of {@code forge.sql} in {@code module-db}.
 *
 * <p>One line per request at INFO (method, URI, status, duration), full
 * headers and bodies at DEBUG. Secrets are masked by {@link Redactor} before
 * anything is written, because CI logs outlive the run that produced them.
 *
 * <p>Placed after the mutating filters, so what gets logged is what actually
 * goes on the wire; placed before the retry filter, so the line reports the
 * final outcome and the total time the test spent on this call.
 */
public class HttpLoggingFilter implements OrderedFilter {

    static final int ORDER = 800;

    private static final Logger log = LoggerFactory.getLogger("forge.http");

    private final Redactor redactor;
    private final boolean bodies;
    private final int maxBodyChars;

    public HttpLoggingFilter(Redactor redactor, boolean bodies, int maxBodyChars) {
        this.redactor = redactor;
        this.bodies = bodies;
        this.maxBodyChars = maxBodyChars;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        long startedAt = System.nanoTime();
        try {
            Response response = ctx.next(requestSpec, responseSpec);
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("{} {} -> {} ({} ms)", requestSpec.getMethod(), requestSpec.getURI(),
                    response.getStatusCode(), millis);
            if (bodies && log.isDebugEnabled()) {
                logDetails(requestSpec, response);
            }
            return response;
        } catch (RuntimeException e) {
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            log.warn("{} {} -> failed after {} ms: {}", requestSpec.getMethod(), requestSpec.getURI(),
                    millis, e.toString());
            throw e;
        }
    }

    private void logDetails(FilterableRequestSpecification requestSpec, Response response) {
        log.debug("""
                        request headers: {}
                        request body: {}
                        response body: {}""",
                headers(requestSpec),
                body(requestSpec.getBody() instanceof String text ? text : null),
                body(responseText(response)));
    }

    private String headers(FilterableRequestSpecification requestSpec) {
        return StreamSupport.stream(requestSpec.getHeaders().spliterator(), false)
                .map(this::header)
                .collect(Collectors.joining(", "));
    }

    private String header(Header header) {
        return header.getName() + "=" + redactor.header(header.getName(), header.getValue());
    }

    private String responseText(Response response) {
        String contentType = response.getContentType();
        if (contentType != null && !isTextual(contentType)) {
            return "<" + contentType + ", not logged>";
        }
        return response.asString();
    }

    private String body(String body) {
        if (body == null) {
            return "<empty>";
        }
        String redacted = redactor.body(body);
        if (redacted.length() <= maxBodyChars) {
            return redacted;
        }
        return redacted.substring(0, maxBodyChars) + "... <truncated, %d chars total>".formatted(redacted.length());
    }

    private static boolean isTextual(String contentType) {
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("json")
                || normalized.contains("xml")
                || normalized.contains("text")
                || normalized.contains("html")
                || normalized.contains("urlencoded");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
