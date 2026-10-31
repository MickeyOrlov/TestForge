package io.testforge.auth;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.util.Optional;

/**
 * Adds the current role's token to every request sent through REST Assured,
 * including the TestForge {@code ApiClient}, which applies every {@code Filter}
 * bean.
 *
 * <p>A header the test set itself is left alone — a test that sends an
 * expired or foreign token on purpose keeps control. With no {@link AsUser},
 * no {@link AuthContext#runAs} and no {@code default-role}, the request goes
 * out anonymous; {@link AuthContext#ANONYMOUS} makes it anonymous even with a
 * default role.
 *
 * <p>Runs after correlation and before logging, so the logged request shows
 * the header (masked by {@code forge.http.logging.redact-headers}).
 */
public class AuthFilter implements OrderedFilter {

    static final int ORDER = 150;

    private final TokenProvider tokens;
    private final AuthProperties properties;

    public AuthFilter(TokenProvider tokens, AuthProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {
        if (AuthContext.anonymous()) {
            return ctx.next(requestSpec, responseSpec);
        }
        Optional<String> role = AuthContext.currentRole().or(() -> Optional.ofNullable(properties.defaultRole())
                .filter(value -> !value.isBlank()));
        if (role.isPresent() && !requestSpec.getHeaders().hasHeaderWithName(properties.header())) {
            String scheme = properties.scheme().isBlank() ? "" : properties.scheme() + " ";
            requestSpec.header(properties.header(), scheme + tokens.forRole(role.get()));
        }
        return ctx.next(requestSpec, responseSpec);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
