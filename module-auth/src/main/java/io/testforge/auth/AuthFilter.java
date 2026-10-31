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
 * <p>The credential goes where the role says ({@link CredentialPlacement}): the
 * {@code forge.auth.header} for tokens, a header or query parameter for an API
 * key, a cookie for a session.
 *
 * <p>A header, parameter or cookie the test set itself is left alone — a test that sends an
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
        role.ifPresent(name -> apply(requestSpec, name));
        return ctx.next(requestSpec, responseSpec);
    }

    private void apply(FilterableRequestSpecification requestSpec, String role) {
        CredentialPlacement placement = tokens.placement(role)
                .orElseGet(() -> CredentialPlacement.header(properties.header(), properties.scheme()));
        switch (placement.kind()) {
            case HEADER -> {
                if (!requestSpec.getHeaders().hasHeaderWithName(placement.name())) {
                    requestSpec.header(placement.name(), placement.prefix() + tokens.forRole(role));
                }
            }
            case QUERY -> {
                if (!requestSpec.getQueryParams().containsKey(placement.name())) {
                    requestSpec.queryParam(placement.name(), tokens.forRole(role));
                }
            }
            case COOKIE -> {
                String cookie = tokens.forRole(role);
                int separator = cookie.indexOf('=');
                String name = cookie.substring(0, separator);
                if (!requestSpec.getCookies().hasCookieWithName(name)) {
                    requestSpec.cookie(name, cookie.substring(separator + 1));
                }
            }
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
