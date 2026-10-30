package io.testforge.auth;

import java.time.Instant;
import java.util.Objects;

/**
 * A bearer credential and the moment it stops being valid.
 *
 * <p>{@link #toString()} never prints the value: a token that reaches a log or
 * an assertion message is a leaked credential.
 *
 * @param value     the raw token sent on the wire
 * @param expiresAt when the token expires, or {@code null} when it does not
 */
public record AccessToken(String value, Instant expiresAt) {

    public AccessToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("token value must not be blank");
        }
    }

    /** True when the token is no longer valid at {@code moment}; a token without expiry never is. */
    public boolean expiresBefore(Instant moment) {
        return expiresAt != null && !expiresAt.isAfter(Objects.requireNonNull(moment, "moment"));
    }

    @Override
    public String toString() {
        return "AccessToken[value=***, expiresAt=" + expiresAt + "]";
    }
}
