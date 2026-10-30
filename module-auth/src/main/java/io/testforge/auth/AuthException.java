package io.testforge.auth;

/** A role could not be given a token. The message never contains a secret. */
public class AuthException extends RuntimeException {

    public AuthException(String message) {
        super(message);
    }

    public AuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
