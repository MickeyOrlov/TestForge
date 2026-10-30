package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class LocalJwtSignerTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private final LocalJwtSigner signer =
            new LocalJwtSigner(SECRET, "testforge", Duration.ofMinutes(15), new MutableClock(NOW));

    private static JsonNode part(String token, int index) throws Exception {
        return new ObjectMapper().readTree(Base64.getUrlDecoder().decode(token.split("\\.")[index]));
    }

    @Test
    void producesAVerifiableHs256Token() throws Exception {
        AccessToken token = signer.sign("user-1", Map.of("roles", "admin"));
        String[] parts = token.value().split("\\.");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));

        assertThat(parts).hasSize(3);
        assertThat(parts[2]).isEqualTo(expected);
        assertThat(part(token.value(), 0).get("alg").asText()).isEqualTo("HS256");
    }

    @Test
    void carriesStandardAndCustomClaims() throws Exception {
        AccessToken token = signer.sign("user-1", Map.of("roles", "admin", "tenant", "demo"));
        JsonNode payload = part(token.value(), 1);

        assertThat(payload.get("iss").asText()).isEqualTo("testforge");
        assertThat(payload.get("sub").asText()).isEqualTo("user-1");
        assertThat(payload.get("iat").asLong()).isEqualTo(NOW.getEpochSecond());
        assertThat(payload.get("exp").asLong()).isEqualTo(NOW.plus(Duration.ofMinutes(15)).getEpochSecond());
        assertThat(payload.get("roles").asText()).isEqualTo("admin");
        assertThat(payload.get("tenant").asText()).isEqualTo("demo");
        assertThat(token.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void customClaimsCannotOverrideReservedOnes() throws Exception {
        JsonNode payload = part(signer.sign("user-1", Map.of("exp", "9999999999", "iss", "evil")).value(), 1);

        assertThat(payload.get("iss").asText()).isEqualTo("testforge");
        assertThat(payload.get("exp").asLong()).isEqualTo(NOW.plus(Duration.ofMinutes(15)).getEpochSecond());
    }

    @Test
    void everyTokenIsUnique() {
        assertThat(signer.sign("u", Map.of()).value()).isNotEqualTo(signer.sign("u", Map.of()).value());
    }

    @Test
    void shortSecretsAreRejected() {
        assertThatThrownBy(() -> new LocalJwtSigner("too-short", "i", Duration.ofMinutes(1), new MutableClock(NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes")
                .hasMessageNotContaining("too-short");
    }
}
