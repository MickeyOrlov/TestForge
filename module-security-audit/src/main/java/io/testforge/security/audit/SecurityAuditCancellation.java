package io.testforge.security.audit;

import java.util.concurrent.atomic.AtomicBoolean;

public final class SecurityAuditCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() {
        cancelled.set(true);
    }

    public boolean cancelled() {
        return cancelled.get();
    }
}
