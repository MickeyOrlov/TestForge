package io.testforge.security;

/** A fixed policy/configuration diagnostic, without raw values or an unsafe third-party cause. */
public final class BusinessSecurityException extends IllegalStateException {
    BusinessSecurityException(String message) {
        super(message);
    }
}
