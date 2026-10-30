package io.testforge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * OAuth2 token endpoint client for the client credentials and password grants
 * ({@code client_secret_post}).
 *
 * <p>Error messages name the role, the endpoint and the status, never the
 * request body: it carries the client secret and possibly a password.
 */
class OAuth2TokenSource implements TokenSource {

    private final String role;
    private final AuthProperties.RoleProperties properties;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    OAuth2TokenSource(String role, AuthProperties.RoleProperties properties, HttpClient http, Duration timeout,
                      Clock clock) {
        this.role = role;
        this.properties = properties;
        this.http = http;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public AccessToken fetch() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.tokenUrl()))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form()))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new AuthException("Token request for role '%s' to %s failed: %s"
                    .formatted(role, properties.tokenUrl(), e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AuthException("Token request for role '%s' was interrupted".formatted(role), e);
        }

        if (response.statusCode() / 100 != 2) {
            throw new AuthException("Token endpoint %s answered %d for role '%s'%s"
                    .formatted(properties.tokenUrl(), response.statusCode(), role, oauthError(response.body())));
        }
        return parse(response.body());
    }

    private String form() {
        Map<String, String> fields = new LinkedHashMap<>();
        if (properties.type() == TokenType.PASSWORD) {
            fields.put("grant_type", "password");
            fields.put("username", properties.username());
            fields.put("password", properties.password());
        } else {
            fields.put("grant_type", "client_credentials");
        }
        fields.put("client_id", properties.clientId());
        if (properties.clientSecret() != null) {
            fields.put("client_secret", properties.clientSecret());
        }
        if (properties.scope() != null && !properties.scope().isBlank()) {
            fields.put("scope", properties.scope());
        }
        return fields.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }

    private AccessToken parse(String body) {
        JsonNode json;
        try {
            json = mapper.readTree(body);
        } catch (IOException e) {
            throw new AuthException("Token endpoint %s returned a non-JSON body for role '%s'"
                    .formatted(properties.tokenUrl(), role), e);
        }
        String token = json.path("access_token").asText("");
        if (token.isBlank()) {
            throw new AuthException("Token endpoint %s returned no access_token for role '%s'"
                    .formatted(properties.tokenUrl(), role));
        }
        Instant expiresAt = json.hasNonNull("expires_in")
                ? clock.instant().plusSeconds(json.get("expires_in").asLong())
                : null;
        return new AccessToken(token, expiresAt);
    }

    /** The standard OAuth2 error fields are safe to show; anything else in the body is not echoed. */
    private String oauthError(String body) {
        try {
            JsonNode json = mapper.readTree(body);
            String error = json.path("error").asText("");
            String description = json.path("error_description").asText("");
            if (error.isBlank()) {
                return "";
            }
            return ": " + error + (description.isBlank() ? "" : " (" + description + ")");
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
