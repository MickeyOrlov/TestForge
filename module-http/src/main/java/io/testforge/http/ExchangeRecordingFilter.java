package io.testforge.http;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Writes every exchange into the {@link HttpExchangeJournal}, redacted the same
 * way the log is. Runs just after the logging filter, so it records the
 * request as finally sent (scope, correlation and auth headers included).
 */
public class ExchangeRecordingFilter implements OrderedFilter {

    static final int ORDER = 850;

    private final HttpExchangeJournal journal;
    private final Redactor redactor;
    private final int maxBodyChars;

    public ExchangeRecordingFilter(HttpExchangeJournal journal, Redactor redactor, int maxBodyChars) {
        this.journal = journal;
        this.redactor = redactor;
        this.maxBodyChars = maxBodyChars;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {
        long startedAt = System.nanoTime();
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : requestSpec.getHeaders()) {
            headers.put(header.getName(), redactor.header(header.getName(), header.getValue()));
        }
        String requestBody = body(requestSpec.getBody() instanceof String text ? text : null);
        try {
            Response response = ctx.next(requestSpec, responseSpec);
            journal.record(new HttpExchangeJournal.Exchange(requestSpec.getMethod(), requestSpec.getURI(),
                    response.getStatusCode(), millisSince(startedAt), headers, requestBody,
                    body(textual(response.getContentType()) ? response.asString() : null), null));
            return response;
        } catch (RuntimeException e) {
            journal.record(new HttpExchangeJournal.Exchange(requestSpec.getMethod(), requestSpec.getURI(), null,
                    millisSince(startedAt), headers, requestBody, null, e.toString()));
            throw e;
        }
    }

    private String body(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        String redacted = redactor.body(body);
        return redacted.length() <= maxBodyChars
                ? redacted
                : redacted.substring(0, maxBodyChars) + "... <truncated, %d chars total>".formatted(redacted.length());
    }

    private static boolean textual(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return true;
        }
        String normalized = contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("json") || normalized.contains("xml") || normalized.startsWith("text/")
                || normalized.contains("form-urlencoded");
    }

    private static long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
