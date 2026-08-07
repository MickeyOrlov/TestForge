package io.testforge.http;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.filter.Filter;
import io.restassured.filter.OrderedFilter;
import io.restassured.specification.RequestSpecification;
import java.util.Comparator;
import java.util.List;

/**
 * Entry point for API tests: a REST Assured {@link RequestSpecification} that
 * already knows the environment.
 *
 * <pre>{@code
 * api.request()
 *         .contentType(ContentType.JSON)
 *         .body(Map.of("amount", 100))
 *         .post("/payments")
 *         .then()
 *         .statusCode(201);
 * }</pre>
 *
 * <p>What the returned specification carries: base URL for the environment,
 * connect/read timeouts, default headers, and the module's filters — scenario
 * scope correlation, request id, logging, optional retry. Everything after
 * that is plain REST Assured; this module deliberately adds no assertion or
 * response DSL of its own.
 *
 * <p>Each call returns a fresh specification, so tests can mutate it freely
 * without leaking into the next request.
 */
public class ApiClient {

    private final HttpProperties properties;
    private final List<Filter> filters;
    private final List<ApiRequestCustomizer> customizers;

    public ApiClient(HttpProperties properties, List<Filter> filters, List<ApiRequestCustomizer> customizers) {
        this.properties = properties;
        this.customizers = List.copyOf(customizers);
        // REST Assured orders filters itself; sorting here keeps the chain
        // deterministic regardless of bean definition order
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(ApiClient::orderOf))
                .toList();
    }

    /** Specification against {@code forge.http.base-url}. */
    public RequestSpecification request() {
        return request(null);
    }

    /** Specification against {@code forge.http.services.<service>.base-url}. */
    public RequestSpecification request(String service) {
        RequestSpecBuilder builder = new RequestSpecBuilder()
                .setBaseUri(properties.resolveBaseUrl(service))
                .setConfig(timeouts())
                .addHeaders(properties.resolveHeaders(service))
                .addFilters(filters);

        // a builder-produced specification carries no response specification and
        // cannot be sent on its own — given() supplies the missing half
        RequestSpecification specification = RestAssured.given().spec(builder.build());
        customizers.forEach(customizer -> customizer.customize(specification, service));
        return specification;
    }

    /** Base URL of the default service — for building links outside REST Assured. */
    public String baseUrl() {
        return properties.resolveBaseUrl(null);
    }

    public String baseUrl(String service) {
        return properties.resolveBaseUrl(service);
    }

    private RestAssuredConfig timeouts() {
        return RestAssuredConfig.newConfig().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", (int) properties.connectTimeout().toMillis())
                .setParam("http.socket.timeout", (int) properties.readTimeout().toMillis()));
    }

    private static int orderOf(Filter filter) {
        return filter instanceof OrderedFilter ordered ? ordered.getOrder() : OrderedFilter.DEFAULT_PRECEDENCE;
    }
}
