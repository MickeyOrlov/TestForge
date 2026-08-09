package io.testforge.api.explorer;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.http.Header;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.restassured.specification.RequestSpecification;
import io.testforge.http.ApiClient;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one place this module talks HTTP, and it does so through
 * {@code module-http}.
 *
 * <p>Everything a project already configured for {@code ApiClient} — base URL
 * per environment, timeouts, authentication customizers, correlation ids,
 * redacted logging, retry policy — applies to exploration traffic unchanged.
 * A second client would have meant configuring all of it twice and having the
 * two drift.
 */
public class ApiClientExchangeExecutor implements ExchangeExecutor {

    private final ApiClient apiClient;
    private final String service;

    public ApiClientExchangeExecutor(ApiClient apiClient, String service) {
        this.apiClient = apiClient;
        this.service = service;
    }

    @Override
    public String baseUrl() {
        return apiClient.baseUrl(service);
    }

    @Override
    public RuntimeExchange execute(PreparedRequest request) {
        CapturedRequest captured = new CapturedRequest();
        long startedAt = System.nanoTime();
        try {
            RequestSpecification specification = apiClient.request(service).filter(captured);
            request.pathParameters().forEach(specification::pathParam);
            request.queryParameters().forEach(specification::queryParam);
            if (request.body() != null) {
                specification.contentType(request.contentType()).body(request.body());
            }

            Response response = specification.request(request.method(), request.pathTemplate());
            return exchange(captured, response, millisSince(startedAt));
        } catch (RuntimeException e) {
            return RuntimeExchange.failed(captured.headers, e.toString(), millisSince(startedAt));
        }
    }

    private RuntimeExchange exchange(CapturedRequest captured, Response response, long durationMillis) {
        Map<String, String> responseHeaders = new LinkedHashMap<>();
        response.getHeaders().forEach(header -> responseHeaders.put(header.getName(), header.getValue()));

        return new RuntimeExchange(
                captured.headers,
                captured.body,
                response.getStatusCode(),
                response.getContentType(),
                responseHeaders,
                response.asString(),
                durationMillis,
                null);
    }

    private long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /**
     * Reads the request after every other filter has finished with it, so the
     * observation records what was actually sent rather than what this module
     * asked for.
     */
    private static final class CapturedRequest implements OrderedFilter {

        private Map<String, String> headers = Map.of();
        private String body;

        @Override
        public Response filter(FilterableRequestSpecification requestSpec,
                               FilterableResponseSpecification responseSpec,
                               FilterContext ctx) {

            Map<String, String> captured = new LinkedHashMap<>();
            for (Header header : requestSpec.getHeaders()) {
                captured.put(header.getName(), header.getValue());
            }
            headers = captured;

            Object requestBody = requestSpec.getBody();
            body = requestBody instanceof String text ? text : null;

            return ctx.next(requestSpec, responseSpec);
        }

        @Override
        public int getOrder() {
            // after module-http's mutating and logging filters (100-800),
            // before its retry filter (lowest precedence)
            return 900;
        }
    }
}
