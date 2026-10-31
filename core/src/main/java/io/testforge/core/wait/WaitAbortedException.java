package io.testforge.core.wait;

/**
 * A wait stopped before its deadline because the value reached a state it was
 * declared never to leave — a job that went {@code Failed} while the test waited
 * for {@code Completed}. A test failure, not a timeout: waiting longer would not
 * have changed the outcome, only delayed it.
 */
public class WaitAbortedException extends AssertionError {

    private final transient Object value;
    private final int polls;

    public WaitAbortedException(String message, Object value, int polls) {
        super(message);
        this.value = value;
        this.polls = polls;
    }

    /** The value that ended the wait. */
    public Object value() {
        return value;
    }

    public int polls() {
        return polls;
    }
}
