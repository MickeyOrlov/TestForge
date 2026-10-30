package io.testforge.mock;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.Cookie;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.QueryParameter;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.Objects;

/**
 * Where a downstream request carries the scenario's scope id: a JSON path in
 * the body (the default), a header, a query parameter or a cookie.
 *
 * <p>One marker drives everything a scope does — stub matching, the request
 * journal and the unmatched-request diagnostics — so the three can never
 * disagree about which traffic belongs to a scenario.
 *
 * <p>Body markers cannot scope requests without a body (GET, DELETE); use a
 * header, query parameter or cookie the system under test forwards instead.
 */
public record ScopeMarker(Kind kind, String name) {

    public enum Kind { BODY_JSON_PATH, HEADER, QUERY_PARAM, COOKIE }

    public ScopeMarker {
        Objects.requireNonNull(kind, "kind");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("scope marker name must not be blank");
        }
    }

    public static ScopeMarker jsonPath(String path) {
        return new ScopeMarker(Kind.BODY_JSON_PATH, path);
    }

    public static ScopeMarker header(String name) {
        return new ScopeMarker(Kind.HEADER, name);
    }

    public static ScopeMarker queryParam(String name) {
        return new ScopeMarker(Kind.QUERY_PARAM, name);
    }

    public static ScopeMarker cookie(String name) {
        return new ScopeMarker(Kind.COOKIE, name);
    }

    MappingBuilder applyTo(MappingBuilder mapping, String scopeId) {
        return switch (kind) {
            case BODY_JSON_PATH -> mapping.withRequestBody(WireMock.matchingJsonPath(name, WireMock.equalTo(scopeId)));
            case HEADER -> mapping.withHeader(name, WireMock.equalTo(scopeId));
            case QUERY_PARAM -> mapping.withQueryParam(name, WireMock.equalTo(scopeId));
            case COOKIE -> mapping.withCookie(name, WireMock.equalTo(scopeId));
        };
    }

    RequestPatternBuilder applyTo(RequestPatternBuilder pattern, String scopeId) {
        return switch (kind) {
            case BODY_JSON_PATH -> pattern.withRequestBody(WireMock.matchingJsonPath(name, WireMock.equalTo(scopeId)));
            case HEADER -> pattern.withHeader(name, WireMock.equalTo(scopeId));
            case QUERY_PARAM -> pattern.withQueryParam(name, WireMock.equalTo(scopeId));
            case COOKIE -> pattern.withCookie(name, WireMock.equalTo(scopeId));
        };
    }

    /** True when {@code request} carries exactly {@code scopeId}; an unreadable request never does. */
    boolean carries(LoggedRequest request, String scopeId) {
        try {
            return switch (kind) {
                case BODY_JSON_PATH -> WireMock.matchingJsonPath(name, WireMock.equalTo(scopeId))
                        .match(request.getBodyAsString()).isExactMatch();
                case HEADER -> {
                    HttpHeader header = request.header(name);
                    yield header != null && header.isPresent() && header.values().contains(scopeId);
                }
                case QUERY_PARAM -> {
                    QueryParameter parameter = request.queryParameter(name);
                    yield parameter != null && parameter.isPresent() && parameter.values().contains(scopeId);
                }
                case COOKIE -> {
                    Cookie cookie = request.getCookies().get(name);
                    yield cookie != null && cookie.getValues().contains(scopeId);
                }
            };
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The body path, for diagnostics that only make sense for body markers. */
    String jsonPathOrNull() {
        return kind == Kind.BODY_JSON_PATH ? name : null;
    }
}
