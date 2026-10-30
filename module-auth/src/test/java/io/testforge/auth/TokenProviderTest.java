package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TokenProviderTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T10:00:00Z"));
    private final AtomicInteger fetches = new AtomicInteger();

    /** Issues token-1, token-2, ... each valid for five minutes. */
    private final TokenSource expiring = () -> new AccessToken(
            "token-" + fetches.incrementAndGet(), clock.instant().plus(Duration.ofMinutes(5)));

    private final TokenProvider provider =
            new TokenProvider(Map.of("admin", expiring), clock, Duration.ofSeconds(30));

    @Test
    void reusesATokenWhileItIsValid() {
        assertThat(provider.forRole("admin")).isEqualTo("token-1");
        clock.advance(Duration.ofMinutes(4));
        assertThat(provider.forRole("admin")).isEqualTo("token-1");
        assertThat(fetches).hasValue(1);
    }

    @Test
    void refreshesShortlyBeforeExpiry() {
        provider.forRole("admin");
        clock.advance(Duration.ofMinutes(4).plusSeconds(31));

        assertThat(provider.forRole("admin")).as("inside the 30s refresh window").isEqualTo("token-2");
    }

    @Test
    void tokensWithoutExpiryAreFetchedOnce() {
        TokenProvider forever = new TokenProvider(
                Map.of("partner", () -> new AccessToken("static-" + fetches.incrementAndGet(), null)),
                clock, Duration.ofSeconds(30));

        forever.forRole("partner");
        clock.advance(Duration.ofDays(365));

        assertThat(forever.forRole("partner")).isEqualTo("static-1");
    }

    @Test
    void invalidateForcesAFreshFetch() {
        provider.forRole("admin");
        provider.invalidate("admin");
        assertThat(provider.forRole("admin")).isEqualTo("token-2");

        provider.invalidateAll();
        assertThat(provider.forRole("admin")).isEqualTo("token-3");
    }

    @Test
    void unknownRoleListsTheConfiguredOnes() {
        TokenProvider several = new TokenProvider(Map.of("admin", expiring, "auditor", expiring),
                clock, Duration.ZERO);

        assertThatThrownBy(() -> several.forRole("root"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'root'")
                .hasMessageContaining("[admin, auditor]");
    }

    @Test
    void aFailedFetchIsNotCached() {
        AtomicInteger attempts = new AtomicInteger();
        TokenProvider flaky = new TokenProvider(Map.of("admin", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new AuthException("idp down");
            }
            return new AccessToken("ok", null);
        }), clock, Duration.ZERO);

        assertThatThrownBy(() -> flaky.forRole("admin")).hasMessage("idp down");
        assertThat(flaky.forRole("admin")).isEqualTo("ok");
    }

    @Test
    void parallelTestsTriggerOneFetchPerRole() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return provider.forRole("admin");
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertThat(result.get()).isEqualTo("token-1");
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(fetches).hasValue(1);
    }

    @Test
    void tokensNeverPrintTheirValue() {
        AccessToken token = new AccessToken("super-secret-value", null);

        assertThat(token.toString()).doesNotContain("super-secret-value").contains("***");
        assertThat(String.valueOf(List.of(token))).doesNotContain("super-secret-value");
    }
}
