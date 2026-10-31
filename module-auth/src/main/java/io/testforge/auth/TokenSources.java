package io.testforge.auth;

import java.net.http.HttpClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one {@link TokenSource} per configured role, validating everything up
 * front: a role with a missing field fails the context at startup, not the
 * first test that happens to use it.
 */
final class TokenSources {

    private TokenSources() {
    }

    static Map<String, TokenSource> from(AuthProperties properties, HttpClient http, Clock clock) {
        List<String> problems = new ArrayList<>();
        Map<String, TokenSource> sources = new LinkedHashMap<>();
        LocalJwtSigner signer = null;

        for (var entry : new java.util.TreeMap<>(properties.roles()).entrySet()) {
            String role = entry.getKey();
            AuthProperties.RoleProperties props = entry.getValue();
            if (AuthContext.ANONYMOUS.equals(role)) {
                problems.add(role + ": reserved name, it means 'no token'; pick another role name");
                continue;
            }
            switch (props.type()) {
                case STATIC -> {
                    if (blank(props.token())) {
                        problems.add(role + ": static role needs 'token'");
                    } else {
                        sources.put(role, staticToken(role, props.token(), clock));
                    }
                }
                case CLIENT_CREDENTIALS, PASSWORD -> {
                    require(problems, role, "token-url", props.tokenUrl());
                    require(problems, role, "client-id", props.clientId());
                    if (props.type() == TokenType.PASSWORD) {
                        require(problems, role, "username", props.username());
                        require(problems, role, "password", props.password());
                    }
                    sources.put(role, new OAuth2TokenSource(role, props, http, properties.requestTimeout(), clock));
                }
                case API_KEY -> {
                    if (blank(props.token())) {
                        problems.add(role + ": api-key role needs 'token'");
                    } else {
                        AccessToken key = new AccessToken(props.token(), null);
                        sources.put(role, () -> key);
                    }
                }
                case SESSION -> {
                    require(problems, role, "session.login-url", props.session().loginUrl());
                    require(problems, role, "username", props.username());
                    require(problems, role, "password", props.password());
                    sources.put(role, new SessionLoginSource(role, props, http, properties.requestTimeout(), clock));
                }
                case LOCAL_JWT -> {
                    if (signer == null) {
                        try {
                            signer = new LocalJwtSigner(properties.localJwt().secret(),
                                    properties.localJwt().issuer(), properties.localJwt().ttl(), clock);
                        } catch (IllegalArgumentException e) {
                            problems.add(role + ": " + e.getMessage());
                            continue;
                        }
                    }
                    LocalJwtSigner roleSigner = signer;
                    sources.put(role, () -> roleSigner.sign(props.subject(), props.claims()));
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new AuthException("Invalid forge.auth.roles configuration: " + String.join("; ", problems));
        }
        return sources;
    }

    /**
     * A static token that is a JWT carries its expiry: the provider then knows
     * when it ends, and an expired one fails with that reason instead of every
     * request coming back 401.
     */
    static TokenSource staticToken(String role, String value, Clock clock) {
        AccessToken token = new AccessToken(value, JwtExpiry.of(value).orElse(null));
        return () -> {
            if (token.expiresBefore(clock.instant())) {
                throw new AuthException(("Static token for role '%s' expired at %s; renew the secret behind "
                        + "forge.auth.roles.%s.token").formatted(role, token.expiresAt(), role));
            }
            return token;
        };
    }

    /** Where each role's credential goes; roles not listed use the global header and scheme. */
    static Map<String, CredentialPlacement> placements(AuthProperties properties) {
        Map<String, CredentialPlacement> placements = new LinkedHashMap<>();
        properties.roles().forEach((role, props) -> {
            switch (props.type()) {
                case API_KEY -> placements.put(role, props.apiKey().in() == AuthProperties.ApiKeyProperties.Placement.QUERY
                        ? CredentialPlacement.query(props.apiKey().name())
                        : CredentialPlacement.header(props.apiKey().name(), null));
                case SESSION -> placements.put(role, CredentialPlacement.cookie());
                default -> placements.put(role, CredentialPlacement.header(properties.header(), properties.scheme()));
            }
        });
        return placements;
    }

    private static void require(List<String> problems, String role, String field, String value) {
        if (blank(value)) {
            problems.add(role + ": needs '" + field + "'");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
