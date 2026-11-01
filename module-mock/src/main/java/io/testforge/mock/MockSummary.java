package io.testforge.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.SmartLifecycle;

/**
 * What the run's closed scopes left over, counted across the suite: stubs no
 * request ever hit and scoped requests no stub answered. Either one passes a
 * test quietly — on the defaults — unless {@code fail-on-unused-stubs} is on.
 * Written as {@code mock-summary.json} to the run index when the context
 * stops, for module-quality's hygiene dimension.
 *
 * <p>Keeps stub descriptions ({@code POST /payments → 503}) and request
 * method and path only — no body, header or query value. Bounded at
 * {@value #MAX_ENTRIES} distinct entries per list.
 */
public class MockSummary implements SmartLifecycle {

    static final String SOURCE = "module-mock";
    static final String FILE = "mock-summary.json";
    static final int MAX_ENTRIES = 500;

    private final ArtifactSink sink;
    private final Map<String, Long> unusedStubs = new TreeMap<>();
    private final Map<String, Long> unmatchedRequests = new TreeMap<>();
    private long scopes;
    private long stubs;
    private long scopesWithUnused;
    private long dropped;
    private volatile boolean running;

    public MockSummary(ArtifactSink sink) {
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    /** One closed scope: how many stubs it registered, which went unused, which of its requests went unmatched. */
    synchronized void record(int stubCount, List<String> unused, List<String> unmatched) {
        scopes++;
        stubs += stubCount;
        if (!unused.isEmpty()) {
            scopesWithUnused++;
        }
        unused.stream().distinct().forEach(stub -> count(unusedStubs, stub));
        unmatched.forEach(request -> count(unmatchedRequests, request));
    }

    private void count(Map<String, Long> counts, String key) {
        if (counts.containsKey(key) || counts.size() < MAX_ENTRIES) {
            counts.merge(key, 1L, Long::sum);
        } else {
            dropped++;
        }
    }

    /** As written to {@code mock-summary.json}. */
    public synchronized Map<String, Object> summary() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("formatVersion", 1);
        json.put("scopes", scopes);
        json.put("stubs", stubs);
        json.put("scopesWithUnusedStubs", scopesWithUnused);
        json.put("unusedStubs", entries(unusedStubs, "stub", "scopes"));
        json.put("unmatchedRequests", entries(unmatchedRequests, "request", "count"));
        json.put("droppedEntries", dropped);
        return json;
    }

    private static List<Map<String, Object>> entries(Map<String, Long> counts, String key, String value) {
        List<Map<String, Object>> entries = new ArrayList<>();
        counts.forEach((name, count) -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(key, name);
            entry.put(value, count);
            entries.add(entry);
        });
        return entries;
    }

    @Override
    public void start() {
        running = true;
    }

    /** Writes the summary into the run index; nothing without one or without a closed scope. */
    @Override
    public void stop() {
        running = false;
        Map<String, Object> summary = summary();
        if ((long) summary.get("scopes") == 0 || sink == ArtifactSink.NO_OP) {
            return;
        }
        try {
            Path file = sink.directoryFor(SOURCE).resolve(FILE);
            Files.createDirectories(file.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), summary);
            sink.registerFile(SOURCE, "mock-summary", FILE, file, "application/json",
                    Map.of("scopes", String.valueOf(summary.get("scopes"))));
        } catch (IOException | RuntimeException e) {
            // best effort: a summary that cannot be written never fails the run
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Before module-reporting closes the run index on shutdown. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }
}
