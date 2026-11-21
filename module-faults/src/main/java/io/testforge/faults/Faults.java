package io.testforge.faults;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Explicit scopes only. Bean construction does not start Docker or contact a controller. */
public final class Faults implements AutoCloseable {
    private final FaultsProperties properties;
    private final FaultBackend backend;
    private final ArtifactSink sink;
    private final Map<String, FaultScope> active = new LinkedHashMap<>();
    private Thread shutdown;
    private boolean closed;
    private boolean cleaned;
    private final Object closing = new Object();
    public Faults(FaultsProperties properties, FaultBackend backend, ArtifactSink sink) {
        this.properties = properties; this.backend = backend; this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }
    public synchronized FaultScope on(String name) {
        if (closed) throw new IllegalStateException("Faults controller is closed");
        String upstream = properties.upstream(name);
        if (shutdown == null) {
            shutdown = new Thread(() -> { try { close(); } catch (RuntimeException ignored) { /* external cleanup required */ } },
                    "testforge-faults-cleanup");
            Runtime.getRuntime().addShutdownHook(shutdown);
        }
        String id = "tf-" + UUID.randomUUID();
        try {
            FaultScope scope = new FaultScope(this, backend.create(id, upstream), id, name);
            active.put(id, scope);
            return scope;
        } catch (IOException | RuntimeException e) { throw new IllegalStateException("Could not create isolated fault proxy"); }
    }
    synchronized void finished(FaultScope scope, String id, String name, String opened,
            List<FaultScope.Event> events, boolean removed) {
        if (removed) active.remove(id);
        if (sink == ArtifactSink.NO_OP) return;
        try {
            var report = Map.of("formatVersion", 1, "scopeId", id, "proxy", name, "openedAt", opened,
                    "closedAt", Instant.now().toString(), "cleanupSucceeded", removed, "events", events.stream().map(event ->
                        Map.of("kind", event.kind(), "value", event.value(), "unit", event.unit(),
                                "startedAt", event.startedAt(), "endedAt", event.endedAt() == null ? Instant.now().toString() : event.endedAt(),
                                "durationMillis", java.time.Duration.between(Instant.parse(event.startedAt()), event.endedAt() == null ? Instant.now() : Instant.parse(event.endedAt())).toMillis()))
                        .toList());
            var file = sink.newFile("module-faults", "faults.json");
            Files.writeString(file, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
            sink.registerFile("module-faults", "faults", "faults.json", file, "application/json", Map.of());
        } catch (IOException | RuntimeException ignored) { /* Evidence never replaces a test or cleanup failure. */ }
    }
    @Override public void close() { synchronized (closing) { closeOwned(); } }
    private void closeOwned() {
        List<FaultScope> scopes;
        synchronized (this) {
            if (cleaned) return;
            closed = true;
            scopes = List.copyOf(active.values());
        }
        RuntimeException failure = null;
        for (FaultScope scope : scopes) {
            try { scope.close(); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        try { backend.close(); } catch (RuntimeException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure == null && shutdown != null && Thread.currentThread() != shutdown) {
            try { Runtime.getRuntime().removeShutdownHook(shutdown); } catch (IllegalStateException ignored) { /* shutting down */ }
        }
        synchronized (this) { cleaned = failure == null; }
        if (failure != null) throw failure;
    }
}
