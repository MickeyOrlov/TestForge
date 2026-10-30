package io.testforge.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached tokens per role. A token is fetched on first use, reused across tests
 * and threads, and fetched again shortly before it expires
 * ({@code forge.auth.refresh-skew}).
 *
 * <p>There is no automatic retry on a 401: a request that is rejected with a
 * fresh token is a finding, not noise. Call {@link #invalidate(String)} when a
 * test deliberately revokes a token.
 */
public class TokenProvider {

    private final Map<String, TokenSource> sources;
    private final Clock clock;
    private final Duration refreshSkew;
    private final Map<String, AccessToken> cache = new ConcurrentHashMap<>();

    public TokenProvider(Map<String, TokenSource> sources, Clock clock, Duration refreshSkew) {
        this.sources = Map.copyOf(sources);
        this.clock = clock;
        this.refreshSkew = refreshSkew;
    }

    /** The raw token for {@code role}, ready for an {@code Authorization} header. */
    public String forRole(String role) {
        return token(role).value();
    }

    public AccessToken token(String role) {
        TokenSource source = sources.get(role);
        if (source == null) {
            throw new IllegalArgumentException("Unknown role '%s'. Configured under forge.auth.roles: %s"
                    .formatted(role, roles()));
        }
        // compute holds the per-key lock: concurrent tests for one role trigger one fetch
        return cache.compute(role, (ignored, cached) ->
                cached == null || cached.expiresBefore(clock.instant().plus(refreshSkew)) ? source.fetch() : cached);
    }

    public void invalidate(String role) {
        cache.remove(role);
    }

    public void invalidateAll() {
        cache.clear();
    }

    public Set<String> roles() {
        return new TreeSet<>(sources.keySet());
    }
}
