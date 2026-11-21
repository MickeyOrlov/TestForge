package io.testforge.mail;

/**
 * A scope refused what the server returned — a shared or foreign message, too many messages. Unlike a
 * failed request this will not change by polling again, so waits stop at once on it.
 */
public final class MailRefusedException extends IllegalStateException {

    MailRefusedException(String message) {
        super(message);
    }
}
