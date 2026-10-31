package io.testforge.auth;

/** How a role obtains its token. */
public enum TokenType {
    /** A fixed token from configuration, typically injected from an environment variable. */
    STATIC,
    /** OAuth2 client credentials grant against {@code token-url}. */
    CLIENT_CREDENTIALS,
    /** OAuth2 resource owner password grant against {@code token-url}; test users only. */
    PASSWORD,
    /** An HS256 JWT signed locally with {@code forge.auth.local-jwt.secret}; no identity provider involved. */
    LOCAL_JWT,
    /** A fixed key from {@code token}, sent in a header or query parameter ({@code api-key.in}, {@code api-key.name}). */
    API_KEY,
    /** A session cookie from one login request to {@code session.login-url} with {@code username} and {@code password}. */
    SESSION
}
