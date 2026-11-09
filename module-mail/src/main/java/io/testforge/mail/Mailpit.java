package io.testforge.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recipient isolation and reporting only. Opening a scope sends no request. */
public final class Mailpit implements AutoCloseable {

    private final MailProperties properties;
    private final MailBackend backend;
    private final ArtifactSink sink;
    private final Map<String, MailScope> active = new HashMap<>();
    private final Object closing = new Object();
    private boolean closed;
    private boolean cleaned;
    private Thread shutdown;

    public Mailpit(MailProperties properties, MailBackend backend, ArtifactSink sink) {
        this.properties = properties;
        this.backend = backend;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    public synchronized MailScope open() {
        properties.validateRun();
        if (closed) {
            throw new IllegalStateException("Mailpit handle is closed");
        }
        if (shutdown == null) {
            shutdown = new Thread(() -> {
                try {
                    close();
                } catch (RuntimeException ignored) {
                    // remote retention is operator-owned
                }
            }, "testforge-mail-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdown);
        }
        String id = UUID.randomUUID().toString();
        MailScope scope = new MailScope(this, backend, properties, id);
        active.put(id, scope);
        return scope;
    }

    /** Called once per scope, when it closes: the scope leaves the active set and its evidence is written. */
    void finished(String id, String openedAt, int seen, int deleted, int shared, int attempts, boolean cleaned) {
        synchronized (this) {
            active.remove(id);
        }
        if (sink == ArtifactSink.NO_OP) {
            return;
        }
        try {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("formatVersion", 1);
            report.put("scopeId", id);
            report.put("openedAt", openedAt);
            report.put("closedAt", Instant.now().toString());
            report.put("observedMessages", seen);
            report.put("deletedMessages", deleted);
            report.put("sharedMessagesLeft", shared);
            report.put("cleanupAttempts", attempts);
            report.put("cleanupSucceeded", cleaned);
            Path file = sink.newFile("module-mail", "mail.json");
            Files.writeString(file, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            sink.registerFile("module-mail", "mail", "mail.json", file, "application/json", Map.of());
        } catch (Exception ignored) {
            // Diagnostics never replace a test/cleanup failure.
        }
    }

    @Override
    public void close() {
        synchronized (closing) {
            closeOwned();
        }
    }

    private void closeOwned() {
        List<MailScope> scopes;
        synchronized (this) {
            if (cleaned) {
                return;
            }
            closed = true;
            scopes = List.copyOf(active.values());
        }
        RuntimeException failure = null;
        for (MailScope scope : scopes) {
            try {
                scope.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        boolean open;
        synchronized (this) {
            open = !active.isEmpty();
        }
        if (!open) {
            backend.close();
            synchronized (this) {
                cleaned = true;
            }
            if (shutdown != null && Thread.currentThread() != shutdown) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException ignored) {
                    // shutting down
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
