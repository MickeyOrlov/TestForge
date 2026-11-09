package io.testforge.mail;

import io.testforge.core.wait.WaitAbortedException;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Exclusive recipient scope; content is retained only for caller assertions, never evidence. */
public final class MailScope implements AutoCloseable {

    private static final String SHARED = "Mail scope requires one matching To and no Cc/Bcc; "
            + "refusing foreign/shared message";

    private final Mailpit owner;
    private final MailBackend backend;
    private final MailProperties properties;
    private final String id;
    private final String openedAt = Instant.now().toString();
    private final Set<String> seen = new HashSet<>();
    private int deleted;
    private int attempts;
    private boolean closed;

    MailScope(Mailpit owner, MailBackend backend, MailProperties properties, String id) {
        this.owner = owner;
        this.backend = backend;
        this.properties = properties;
        this.id = id;
    }

    public synchronized String recipient() {
        ensureOpen();
        return address();
    }

    private String address() {
        return "tf-" + id + "@testforge.invalid";
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Mail scope is closed");
        }
    }

    private static Duration remaining(long started, Duration budget) {
        Duration left = budget.minusNanos(System.nanoTime() - started);
        if (left.toMillis() < 1) {
            throw new IllegalStateException("Mail operation deadline expired");
        }
        return left;
    }

    /** The message when it is this scope's alone, {@code null} when it is shared or foreign. */
    private MailMessage exclusive(String messageId, Duration budget) {
        MailMessage message = backend.fetch(messageId, budget);
        return message == null || !messageId.equals(message.id()) || !message.ownedBy(address()) ? null : message;
    }

    private MailMessage owned(String messageId, Duration budget) {
        MailMessage message = exclusive(messageId, budget);
        if (message == null) {
            throw new MailRefusedException(SHARED);
        }
        seen.add(messageId);
        return message;
    }

    private List<String> search(Duration budget) {
        List<String> ids = backend.search(address(), budget);
        if (ids.size() > properties.maxMessages()) {
            throw new MailRefusedException("Mail scope message limit exceeded");
        }
        return List.copyOf(ids);
    }

    /** Every message currently addressed to this scope. */
    public synchronized List<MailMessage> messages() {
        ensureOpen();
        long started = System.nanoTime();
        Duration budget = properties.awaitTimeout();
        List<MailMessage> messages = new ArrayList<>();
        for (String messageId : search(remaining(started, budget))) {
            messages.add(owned(messageId, remaining(started, budget)));
        }
        return List.copyOf(messages);
    }

    /**
     * Waits for a message this scope has not returned yet ({@link #messages()} counts as returned).
     * A refusal — a shared message, too many messages — stops the wait at once; a failed request is
     * polled again until the deadline.
     */
    public synchronized MailMessage awaitMessage() {
        ensureOpen();
        long started = System.nanoTime();
        Duration budget = properties.awaitTimeout();
        AtomicReference<MailRefusedException> refused = new AtomicReference<>();
        Waiter waiter = new Waiter(new WaitProperties(budget, properties.pollInterval()));
        try {
            return waiter.await("a recipient-isolated mail message", () -> {
                try {
                    for (String messageId : search(remaining(started, budget))) {
                        if (!seen.contains(messageId)) {
                            return new Poll(owned(messageId, remaining(started, budget)));
                        }
                    }
                    return new Poll(null);
                } catch (MailRefusedException e) {
                    refused.set(e);
                    return new Poll(null);
                }
            }, poll -> poll.message() != null, poll -> refused.get() != null).message();
        } catch (WaitAbortedException e) {
            throw refused.get();
        }
    }

    /** One poll's outcome; prints no content into a timeout message. */
    private record Poll(MailMessage message) {
        @Override
        public String toString() {
            return message == null ? "no new message" : "a new message";
        }
    }

    public synchronized byte[] attachment(String messageId, String partId) {
        ensureOpen();
        long started = System.nanoTime();
        Duration budget = properties.awaitTimeout();
        if (!search(remaining(started, budget)).contains(messageId)) {
            throw new IllegalArgumentException("Attachment request refers to a foreign message");
        }
        MailMessage message = owned(messageId, remaining(started, budget));
        if (message.attachments().stream().noneMatch(a -> a.partId().equals(partId))) {
            throw new IllegalArgumentException("Attachment does not belong to the scoped message");
        }
        return backend.attachment(messageId, partId, remaining(started, budget));
    }

    /**
     * Deletes the messages addressed to this scope alone. A shared message is left on the server —
     * never deleted — and reported after the scope is closed; a failed request leaves the scope open,
     * so the close can be retried. Evidence is written once, when the scope closes.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        attempts++;
        long started = System.nanoTime();
        Duration budget = properties.awaitTimeout();
        List<String> exclusive = new ArrayList<>();
        int shared = 0;
        try {
            for (String messageId : search(remaining(started, budget))) {
                if (exclusive(messageId, remaining(started, budget)) == null) {
                    shared++;
                } else {
                    seen.add(messageId);
                    exclusive.add(messageId);
                }
            }
        } catch (MailRefusedException e) {
            finish(0, false);
            throw e;
        }
        if (!exclusive.isEmpty()) {
            backend.delete(exclusive, remaining(started, budget));
            deleted += exclusive.size();
        }
        finish(shared, shared == 0);
        if (shared > 0) {
            throw new MailRefusedException(shared + " shared message(s) addressed to this scope were left on "
                    + "the server, not deleted: " + SHARED);
        }
    }

    private void finish(int shared, boolean cleaned) {
        closed = true;
        owner.finished(id, openedAt, seen.size(), deleted, shared, attempts, cleaned);
    }
}
