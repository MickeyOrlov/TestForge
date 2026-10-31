package io.testforge.db.state;

import java.time.Duration;
import java.util.function.Supplier;

/** "A failed action leaves nothing behind", shared by table and multi-table snapshots. */
final class Rollback {

    private Rollback() {
    }

    static Throwable expect(DbAction action, Supplier<Object> diff, Supplier<Boolean> unchanged) {
        Throwable failure = null;
        try {
            action.run();
        } catch (Throwable thrown) {
            failure = thrown;
        }
        if (failure == null) {
            throw new AssertionError("expected the action to fail, but it succeeded; it changed:\n" + diff.get());
        }
        if (failure instanceof VirtualMachineError error) {
            throw error;
        }
        if (!unchanged.get()) {
            throw new AssertionError("the action failed (" + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage().strip().lines().findFirst().orElse(""))
                    + ") but left changes behind — it is not atomic:\n" + diff.get(), failure);
        }
        return failure;
    }

    static void within(Duration timeout, Supplier<Object> diff, Supplier<Boolean> unchanged) {
        java.time.Duration interval = timeout.dividedBy(10).compareTo(Duration.ofMillis(200)) > 0
                ? Duration.ofMillis(200) : timeout.dividedBy(10);
        try {
            new io.testforge.core.wait.Waiter(new io.testforge.core.wait.WaitProperties(timeout,
                    interval.isZero() ? Duration.ofMillis(1) : interval))
                    .awaitTrue("changes undone", unchanged::get);
        } catch (org.awaitility.core.ConditionTimeoutException e) {
            throw new AssertionError("still changed after " + timeout + " — the compensation did not undo:\n"
                    + diff.get());
        }
    }
}
