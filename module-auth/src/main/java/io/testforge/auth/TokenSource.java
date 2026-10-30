package io.testforge.auth;

/** Obtains a fresh token for one role. Caching is the provider's job, not the source's. */
@FunctionalInterface
public interface TokenSource {

    AccessToken fetch();
}
