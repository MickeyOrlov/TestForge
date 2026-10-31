package io.testforge.core.wait;

import org.awaitility.core.ConditionTimeoutException;

/**
 * A wait that ran out of time, with what it saw: the last value the condition
 * looked at and the last error a poll threw. Without them "did not complete
 * within 30 seconds" hides whether the job was still {@code Running} or had
 * gone {@code Failed}, and whether every poll silently failed with a 500.
 *
 * <p>Extends Awaitility's exception, so code that catches
 * {@link ConditionTimeoutException} keeps working.
 */
public class WaitTimeoutException extends ConditionTimeoutException {

    private final transient Object lastValue;
    private final transient Throwable lastError;
    private final int polls;

    public WaitTimeoutException(String message, Object lastValue, Throwable lastError, int polls, Throwable cause) {
        super(message, cause);
        this.lastValue = lastValue;
        this.lastError = lastError;
        this.polls = polls;
    }

    /** The value the last poll produced; {@code null} when none did. */
    public Object lastValue() {
        return lastValue;
    }

    /** The error the last failing poll threw; {@code null} when no poll failed. */
    public Throwable lastError() {
        return lastError;
    }

    public int polls() {
        return polls;
    }
}
