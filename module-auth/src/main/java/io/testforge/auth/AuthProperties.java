package io.testforge.auth;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tokens per role, so tests say who they act as instead of how to log in.
 *
 * <pre>
 * forge:
 *   auth:
 *     enabled: true
 *     default-role: user            # optional; requests without @AsUser use it
 *     roles:
 *       admin:
 *         type: client-credentials
 *         token-url: https://idp.staging.example.test/oauth/token
 *         client-id: tests-admin
 *         client-secret: ${TESTS_ADMIN_SECRET}
 *         scope: admin
 *       user:
 *         type: local-jwt
 *         subject: user-1
 *         claims:
 *           roles: user
 *       partner:
 *         type: static
 *         token: ${PARTNER_TOKEN}
 *       integration:
 *         type: api-key
 *         token: ${INTEGRATION_KEY}
 *         api-key:
 *           in: header                # or query
 *           name: X-API-Key           # default
 *       web:
 *         type: session
 *         username: tests-web
 *         password: ${WEB_PASSWORD}
 *         session:
 *           login-url: https://app.staging.example.test/login
 *           format: json              # or form
 *           cookie: SESSION           # optional; default the first cookie set
 *     local-jwt:
 *       issuer: testforge
 *       secret: ${LOCAL_JWT_SECRET}   # at least 32 bytes
 *       ttl: 15m
 * </pre>
 *
 * <p>Secrets belong in environment variables or a secret manager, never in a
 * committed profile.
 */
@ConfigurationProperties(prefix = "forge.auth")
public record AuthProperties(
        boolean enabled,
        String defaultRole,
        String header,
        String scheme,
        Duration refreshSkew,
        Duration requestTimeout,
        Map<String, RoleProperties> roles,
        LocalJwtProperties localJwt) {

    public AuthProperties {
        if (header == null || header.isBlank()) {
            header = "Authorization";
        }
        if (scheme == null) {
            scheme = "Bearer";
        }
        if (refreshSkew == null || refreshSkew.isNegative()) {
            refreshSkew = Duration.ofSeconds(30);
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            requestTimeout = Duration.ofSeconds(10);
        }
        roles = Map.copyOf(roles == null ? Map.of() : roles);
        if (localJwt == null) {
            localJwt = new LocalJwtProperties(null, null, null);
        }
    }

    /** One role a test can act as. Which fields are needed depends on {@link #type()}. */
    public record RoleProperties(
            TokenType type,
            String token,
            String tokenUrl,
            String clientId,
            String clientSecret,
            String username,
            String password,
            String scope,
            String subject,
            Map<String, String> claims,
            ApiKeyProperties apiKey,
            SessionProperties session) {

        public RoleProperties {
            if (type == null) {
                type = TokenType.STATIC;
            }
            claims = Map.copyOf(claims == null ? Map.of() : claims);
            if (apiKey == null) {
                apiKey = new ApiKeyProperties(null, null);
            }
            if (session == null) {
                session = new SessionProperties(null, null, null, null, null);
            }
        }
    }

    /** Where {@link TokenType#API_KEY} puts the key. */
    public record ApiKeyProperties(Placement in, String name) {

        public enum Placement { HEADER, QUERY }

        public ApiKeyProperties {
            if (in == null) {
                in = Placement.HEADER;
            }
            if (name == null || name.isBlank()) {
                name = "X-API-Key";
            }
        }
    }

    /**
     * How {@link TokenType#SESSION} logs in: one request with the role's
     * {@code username} and {@code password}, answered with a session cookie.
     */
    public record SessionProperties(String loginUrl, Format format, String usernameField, String passwordField,
                                    String cookie) {

        public enum Format { JSON, FORM }

        public SessionProperties {
            if (format == null) {
                format = Format.JSON;
            }
            if (usernameField == null || usernameField.isBlank()) {
                usernameField = "username";
            }
            if (passwordField == null || passwordField.isBlank()) {
                passwordField = "password";
            }
        }
    }

    /** Settings for {@link TokenType#LOCAL_JWT}: shared by every local-jwt role. */
    public record LocalJwtProperties(String issuer, String secret, Duration ttl) {

        public LocalJwtProperties {
            if (issuer == null || issuer.isBlank()) {
                issuer = "testforge";
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                ttl = Duration.ofMinutes(15);
            }
        }
    }
}
