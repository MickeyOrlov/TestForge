package io.testforge.mail;

import io.testforge.core.wait.Waiter;
import io.testforge.core.wait.WaitProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Exclusive recipient scope; content is retained only for caller assertions, never evidence. */
public final class MailScope implements AutoCloseable {
    private final Mailpit owner;
    private final MailBackend backend;
    private final MailProperties properties;
    private final String id;
    private final String openedAt = Instant.now().toString();
    private final Set<String> seen = new HashSet<>();
    private int deleted;
    private boolean closed;
    MailScope(Mailpit owner, MailBackend backend, MailProperties properties, String id) {
        this.owner = owner; this.backend = backend; this.properties = properties; this.id = id;
    }
    public synchronized String recipient() { ensureOpen(); return "tf-" + id + "@testforge.invalid"; }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Mail scope is closed"); }
    private Duration remaining(long started, Duration budget) {
        Duration left = budget.minusNanos(System.nanoTime() - started);
        if (left.toMillis() < 1) throw new IllegalStateException("Mail operation deadline expired");
        return left;
    }
    private MailMessage owned(String messageId, Duration budget) {
        MailMessage message = backend.fetch(messageId, budget);
        if (message == null || !messageId.equals(message.id()) || !message.ownedBy(recipient()))
            throw new IllegalStateException("Mail scope requires one matching To and no Cc/Bcc; refusing foreign/shared message");
        seen.add(messageId);
        return message;
    }
    private List<String> search(Duration budget) {
        var ids = backend.search(recipient(), budget);
        if (ids.size() > properties.maxMessages()) throw new IllegalStateException("Mail scope message limit exceeded");
        return List.copyOf(ids);
    }
    public synchronized List<MailMessage> messages() {
        ensureOpen();
        long started = System.nanoTime(); Duration budget = properties.awaitTimeout();
        var ids = search(remaining(started, budget));
        if (ids.size() > properties.maxMessages()) throw new IllegalStateException("Mail scope message limit exceeded");
        List<MailMessage> messages = new ArrayList<>();
        for (String id : ids) messages.add(owned(id, remaining(started, budget)));
        return List.copyOf(messages);
    }
    public synchronized MailMessage awaitMessage() {
        ensureOpen(); long started = System.nanoTime(); Duration budget = properties.awaitTimeout();
        var waiter = new Waiter(new WaitProperties(budget, properties.pollInterval()));
        return waiter.await("a recipient-isolated mail message", () -> {
            var ids = search(remaining(started, budget));
            return ids.isEmpty() ? null : owned(ids.getFirst(), remaining(started, budget));
        }, Objects::nonNull);
    }
    public synchronized byte[] attachment(String messageId, String partId) {
        ensureOpen(); long started = System.nanoTime(); Duration budget = properties.awaitTimeout();
        if (!search(remaining(started, budget)).contains(messageId))
            throw new IllegalArgumentException("Attachment request refers to a foreign message");
        var message = owned(messageId, remaining(started, budget));
        if (message.attachments().stream().noneMatch(a -> a.partId().equals(partId)))
            throw new IllegalArgumentException("Attachment does not belong to the scoped message");
        return backend.attachment(messageId, partId, remaining(started, budget));
    }
    @Override public synchronized void close() {
        if (closed) return;
        boolean cleaned = false;
        try {
            long started = System.nanoTime(); Duration budget = properties.awaitTimeout();
            var ids = search(remaining(started, budget));
            List<String> owned = new ArrayList<>();
            for (String id : ids) { owned(id, remaining(started, budget)); owned.add(id); }
            if (!owned.isEmpty()) { backend.delete(owned, remaining(started, budget)); deleted += owned.size(); }
            closed = true; cleaned = true;
        } finally { owner.finished(this, id, openedAt, seen.size(), deleted, cleaned); }
    }
}
