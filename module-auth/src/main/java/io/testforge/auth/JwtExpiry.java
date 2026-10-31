package io.testforge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The {@code exp} claim of a token that looks like a JWT, read without
 * verifying anything: enough to tell that a static token from a CI secret ran
 * out, never enough to trust it.
 */
final class JwtExpiry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JwtExpiry() {
    }

    static Optional<Instant> of(String token) {
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            JsonNode payload = MAPPER.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return payload.path("exp").isNumber()
                    ? Optional.of(Instant.ofEpochSecond(payload.get("exp").asLong()))
                    : Optional.empty();
        } catch (Exception e) {
            // not a JWT after all: an opaque token with dots in it
            return Optional.empty();
        }
    }
}
