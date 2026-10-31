package io.testforge.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs in with one request and keeps the session cookie the answer sets.
 *
 * <p>The login may answer 2xx or redirect (3xx): redirects are not followed,
 * the cookie comes with the redirect itself. The credential is
 * {@code name=value}, ready for a {@code Cookie} header; its expiry is the
 * cookie's {@code Max-Age}, if it has one.
 *
 * <p>Error messages name the role, the URL and the status, never the request
 * body: it carries the password.
 */
class SessionLoginSource implements TokenSource {

    private final String role;
    private final AuthProperties.RoleProperties properties;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;

    SessionLoginSource(String role, AuthProperties.RoleProperties properties, HttpClient http, Duration timeout,
                       Clock clock) {
        this.role = role;
        this.properties = properties;
        this.http = http;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public AccessToken fetch() {
        AuthProperties.SessionProperties session = properties.session();
        HttpRequest request = HttpRequest.newBuilder(URI.create(session.loginUrl()))
                .timeout(timeout)
                .header("Content-Type", session.format() == AuthProperties.SessionProperties.Format.FORM
                        ? "application/x-www-form-urlencoded" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(session)))
                .build();

        HttpResponse<Void> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (IOException e) {
            throw new AuthException("Login for role '%s' to %s failed: %s"
                    .formatted(role, session.loginUrl(), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Login for role '%s' was interrupted".formatted(role), e);
        }

        int status = response.statusCode();
        if (status / 100 != 2 && status / 100 != 3) {
            throw new AuthException("Login %s answered %d for role '%s'".formatted(session.loginUrl(), status, role));
        }
        return cookie(response.headers().allValues("set-cookie"), session);
    }

    private AccessToken cookie(List<String> headers, AuthProperties.SessionProperties session) {
        for (String header : headers) {
            for (HttpCookie cookie : HttpCookie.parse(header)) {
                if (session.cookie() == null || session.cookie().equals(cookie.getName())) {
                    Instant expiresAt = cookie.getMaxAge() > 0 ? clock.instant().plusSeconds(cookie.getMaxAge()) : null;
                    return new AccessToken(cookie.getName() + "=" + cookie.getValue(), expiresAt);
                }
            }
        }
        throw new AuthException("Login %s for role '%s' set no %s".formatted(session.loginUrl(), role,
                session.cookie() == null ? "cookie" : "cookie named " + session.cookie()));
    }

    private String body(AuthProperties.SessionProperties session) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(session.usernameField(), properties.username());
        fields.put(session.passwordField(), properties.password());
        if (session.format() == AuthProperties.SessionProperties.Format.FORM) {
            StringBuilder form = new StringBuilder();
            fields.forEach((name, value) -> form.append(form.isEmpty() ? "" : "&")
                    .append(URLEncoder.encode(name, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
            return form.toString();
        }
        try {
            return new ObjectMapper().writeValueAsString(fields);
        } catch (IOException e) {
            throw new AuthException("Could not build the login body for role '%s'".formatted(role), e);
        }
    }
}
