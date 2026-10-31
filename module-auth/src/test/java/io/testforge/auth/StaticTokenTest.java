package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StaticTokenTest {

    private static final Instant NOW = Instant.parse("2026-01-01T10:00:00Z");

    private static String jwt(String payload) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    @Test
    void aStaticJwtCarriesItsExpirySoTheProviderKnowsWhenItEnds() {
        String token = jwt("{\"sub\":\"p\",\"exp\":" + NOW.plusSeconds(3600).getEpochSecond() + "}");

        AccessToken fetched = TokenSources.staticToken("partner", token, new MutableClock(NOW)).fetch();

        assertThat(fetched.value()).isEqualTo(token);
        assertThat(fetched.expiresAt()).isEqualTo(NOW.plusSeconds(3600));
    }

    @Test
    void anExpiredStaticJwtFailsWithTheReasonInsteadOfA401Later() {
        MutableClock clock = new MutableClock(NOW);
        String token = jwt("{\"exp\":" + NOW.plusSeconds(60).getEpochSecond() + "}");
        TokenProvider provider = new TokenProvider(
                Map.of("partner", TokenSources.staticToken("partner", token, clock)), clock, Duration.ZERO);
        assertThat(provider.forRole("partner")).isEqualTo(token);

        clock.advance(Duration.ofMinutes(2));

        assertThatThrownBy(() -> provider.forRole("partner"))
                .isInstanceOf(AuthException.class)
                .hasMessageContaining("Static token for role 'partner' expired at 2026-01-01T10:01:00Z")
                .hasMessageContaining("forge.auth.roles.partner.token")
                .hasMessageNotContaining(token);
    }

    @Test
    void anOpaqueTokenNeverExpires() {
        AccessToken fetched = TokenSources.staticToken("partner", "opaque.with.dots", new MutableClock(NOW)).fetch();

        assertThat(fetched.expiresAt()).isNull();
        assertThat(TokenSources.staticToken("p", "plain", new MutableClock(NOW)).fetch().expiresAt()).isNull();
    }
}
