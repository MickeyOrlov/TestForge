package io.testforge.faults;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** One independently routed proxy. Always close it, preferably with try-with-resources. */
public final class FaultScope implements AutoCloseable {
    public record Event(String kind, long value, String unit, String startedAt, String endedAt) { }
    private final Faults owner;
    private final FaultBackend.Handle handle;
    private final String id;
    private final String name;
    private final String opened = Instant.now().toString();
    private final List<Event> events = new ArrayList<>();
    private boolean closed;
    private boolean hasLatency;
    FaultScope(Faults owner, FaultBackend.Handle handle, String id, String name) {
        this.owner = owner; this.handle = handle; this.id = id; this.name = name;
    }
    public synchronized FaultBackend.Endpoint endpoint() { ensureOpen(); return handle.endpoint(); }
    public synchronized FaultScope latency(Duration delay) {
        FaultScope scope = apply("latency", millis(delay), "ms"); hasLatency = true; return scope;
    }
    public synchronized FaultScope jitter(Duration jitter) {
        if (!hasLatency) throw new IllegalStateException("Set latency before jitter");
        return apply("jitter", millis(jitter), "ms");
    }
    public synchronized FaultScope timeout(Duration timeout) { return apply("timeout", millis(timeout), "ms"); }
    public synchronized FaultScope resetPeer() { return apply("reset_peer", 0, "ms"); }
    public synchronized FaultScope bandwidth(long kilobytesPerSecond) {
        if (kilobytesPerSecond < 1) throw new IllegalArgumentException("Bandwidth must be positive KB/s");
        return apply("bandwidth", kilobytesPerSecond, "KB/s");
    }
    public synchronized FaultScope restore() {
        apply("restore", 0, "restored"); hasLatency = false; return this;
    }
    public synchronized FaultScope down() { return apply("down", 0, "disabled"); }
    private long millis(Duration value) {
        if (value == null || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0
                || (!value.isZero() && value.toMillis() == 0)) throw new IllegalArgumentException("Use 0..1d in whole milliseconds");
        return value.toMillis();
    }
    private FaultScope apply(String kind, long value, String unit) {
        ensureOpen();
        try { handle.apply(kind, value); }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("Could not apply network fault; close the scope to clean up"); }
        String applied = Instant.now().toString();
        if (kind.equals("restore")) {
            events.replaceAll(e -> e.endedAt() == null ? new Event(e.kind(), e.value(), e.unit(), e.startedAt(), applied) : e);
        }
        events.add(new Event(kind, value, unit, applied, kind.equals("restore") ? applied : null));
        return this;
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("Fault scope is closed"); }
    @Override public synchronized void close() {
        if (closed) return;
        boolean removed = false;
        try { handle.delete(); removed = true; closed = true; }
        catch (IOException | RuntimeException e) { throw new IllegalStateException("Network fault cleanup failed; owned proxy still requires cleanup"); }
        finally { owner.finished(this, id, name, opened, List.copyOf(events), removed); }
    }
}
