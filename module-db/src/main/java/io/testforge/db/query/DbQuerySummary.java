package io.testforge.db.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * Every statement the run executed, aggregated by shape (literals folded):
 * how often, how slow, how often it failed — a failure the application
 * swallowed included — and whether it wrote every row of a table. Written as
 * {@code db-queries.json} to the run index when the context stops, for
 * module-quality's db-queries dimension. Only shapes are kept, never a
 * parameter; at most {@value #MAX_SHAPES} of them.
 */
public class DbQuerySummary implements SmartLifecycle {

    static final String SOURCE = "module-db";
    static final String FILE = "db-queries.json";
    static final int MAX_SHAPES = 1000;
    static final int MAX_SHAPE_CHARS = 300;

    private final ArtifactSink sink;
    private final long slowMillis;
    private final Map<String, Shape> shapes = new LinkedHashMap<>();
    private long statements;
    private long dropped;
    private volatile boolean running;

    public DbQuerySummary(QueryRecorder recorder, ArtifactSink sink, long slowMillis) {
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
        this.slowMillis = slowMillis;
        recorder.listen(this::record);
    }

    synchronized void record(CapturedQueries.Statement statement) {
        statements += Math.max(1, statement.batch());
        String shape = statement.shape();
        if (shape.length() > MAX_SHAPE_CHARS) {
            shape = shape.substring(0, MAX_SHAPE_CHARS) + "…";
        }
        Shape aggregate = shapes.get(shape);
        if (aggregate == null) {
            if (shapes.size() >= MAX_SHAPES) {
                dropped++;
                return;
            }
            aggregate = new Shape(statement.kind().name(), statement.unboundedWrite());
            shapes.put(shape, aggregate);
        }
        aggregate.count += Math.max(1, statement.batch());
        aggregate.totalMillis += statement.millis();
        aggregate.maxMillis = Math.max(aggregate.maxMillis, statement.millis());
        if (statement.error() != null) {
            aggregate.errors++;
        }
    }

    /** As written to {@code db-queries.json}: the busiest shapes first. */
    public synchronized Map<String, Object> summary() {
        List<Map<String, Object>> rows = new ArrayList<>();
        shapes.entrySet().stream()
                .sorted(Comparator.comparingLong((Map.Entry<String, Shape> e) -> e.getValue().count).reversed())
                .forEach(e -> {
                    Shape s = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("shape", e.getKey());
                    row.put("kind", s.kind);
                    row.put("count", s.count);
                    row.put("errors", s.errors);
                    row.put("maxMillis", s.maxMillis);
                    row.put("totalMillis", s.totalMillis);
                    row.put("unboundedWrite", s.unboundedWrite);
                    row.put("slow", s.maxMillis >= slowMillis);
                    rows.add(row);
                });
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("formatVersion", 1);
        json.put("statements", statements);
        json.put("slowMillis", slowMillis);
        json.put("droppedShapes", dropped);
        json.put("shapes", rows);
        return json;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        Map<String, Object> json = summary();
        if (statements == 0 || sink == ArtifactSink.NO_OP) {
            return;
        }
        try {
            Path file = sink.newFile(SOURCE, FILE);
            Files.createDirectories(file.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), json);
            sink.registerFile(SOURCE, "db-queries", FILE, file, "application/json",
                    Map.of("statements", String.valueOf(statements)));
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

    private static final class Shape {

        final String kind;
        final boolean unboundedWrite;
        long count;
        long errors;
        long maxMillis;
        long totalMillis;

        Shape(String kind, boolean unboundedWrite) {
            this.kind = kind;
            this.unboundedWrite = unboundedWrite;
        }
    }
}
