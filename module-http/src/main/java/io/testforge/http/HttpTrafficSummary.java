package io.testforge.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.context.SmartLifecycle;

/**
 * What the suite's requests met, per operation: how many, which status
 * classes, transport failures, and latency. A 5xx a test expected — or
 * swallowed — still counts here: a passing suite can sit on a failing
 * service. Written as {@code http-summary.json} to the run index when the
 * context stops, for module-quality's reliability dimension.
 *
 * <p>An operation is the method and the path shape — {@code /orders/42}
 * reads {@code /orders/{n}} — without host or query, so runs on different
 * ports compare. Bounded: at most {@value #MAX_OPERATIONS} operations and
 * {@value #MAX_SAMPLES} latency samples each; no body, header or query value
 * is kept.
 */
public class HttpTrafficSummary implements OrderedFilter, SmartLifecycle {

    static final String SOURCE = "module-http";
    static final String FILE = "http-summary.json";
    static final int MAX_OPERATIONS = 500;
    static final int MAX_SAMPLES = 2000;
    static final String OTHER = "OTHER (operation limit reached)";

    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern IDENTIFIER = Pattern.compile("(?=.*\\d)[A-Za-z0-9_-]{16,}");

    private final ArtifactSink sink;
    private final Map<String, Operation> operations = new TreeMap<>();
    private volatile boolean running;

    public HttpTrafficSummary(ArtifactSink sink) {
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    /** After the exchange recorder: the response as the test sees it. */
    @Override
    public int getOrder() {
        return ExchangeRecordingFilter.ORDER + 10;
    }

    @Override
    public Response filter(FilterableRequestSpecification request, FilterableResponseSpecification response,
                           FilterContext context) {
        long started = System.nanoTime();
        String key = request.getMethod().toUpperCase(Locale.ROOT) + " " + shape(request.getURI());
        try {
            Response received = context.next(request, response);
            record(key, received.getStatusCode(), (System.nanoTime() - started) / 1_000_000);
            return received;
        } catch (RuntimeException e) {
            record(key, -1, (System.nanoTime() - started) / 1_000_000);
            throw e;
        }
    }

    /** {@code /orders/42/items/7f3c…} → {@code /orders/{n}/items/{uuid}}. */
    static String shape(String uri) {
        String path;
        try {
            path = URI.create(uri).getPath();
        } catch (RuntimeException e) {
            return "/{unparsable}";
        }
        if (path == null || path.isEmpty()) {
            return "/";
        }
        StringBuilder out = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                continue;
            }
            out.append('/').append(NUMBER.matcher(segment).matches() ? "{n}"
                    : UUID.matcher(segment).matches() ? "{uuid}"
                    : IDENTIFIER.matcher(segment).matches() ? "{id}" : segment);
        }
        return out.isEmpty() ? "/" : out.toString();
    }

    synchronized void record(String key, int status, long millis) {
        String name = operations.containsKey(key) || operations.size() < MAX_OPERATIONS ? key : OTHER;
        operations.computeIfAbsent(name, ignored -> new Operation()).add(status, millis);
    }

    /** Per operation, as written to {@code http-summary.json}. */
    public synchronized List<Map<String, Object>> operations() {
        List<Map<String, Object>> result = new ArrayList<>();
        operations.forEach((name, operation) -> result.add(operation.describe(name)));
        return result;
    }

    @Override
    public void start() {
        running = true;
    }

    /** Writes the summary into the run index; nothing without one or without traffic. */
    @Override
    public void stop() {
        running = false;
        List<Map<String, Object>> summary = operations();
        if (summary.isEmpty() || sink == ArtifactSink.NO_OP) {
            return;
        }
        try {
            Path file = sink.directoryFor(SOURCE).resolve(FILE);
            Files.createDirectories(file.getParent());
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("formatVersion", 1);
            json.put("operations", summary);
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), json);
            sink.registerFile(SOURCE, "http-summary", FILE, file, "application/json",
                    Map.of("operations", String.valueOf(summary.size())));
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

    private static final class Operation {

        private long requests;
        private long transportErrors;
        private final Map<String, Long> statuses = new TreeMap<>();
        private final long[] samples = new long[MAX_SAMPLES];
        private int sampled;
        private long max;

        void add(int status, long millis) {
            requests++;
            if (status < 0) {
                transportErrors++;
            } else {
                statuses.merge(status / 100 + "xx", 1L, Long::sum);
            }
            samples[(int) ((requests - 1) % MAX_SAMPLES)] = millis;
            sampled = (int) Math.min(requests, MAX_SAMPLES);
            max = Math.max(max, millis);
        }

        Map<String, Object> describe(String name) {
            long[] sorted = Arrays.copyOf(samples, sampled);
            Arrays.sort(sorted);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("operation", name);
            out.put("requests", requests);
            out.put("status", new LinkedHashMap<>(statuses));
            out.put("transportErrors", transportErrors);
            out.put("p50Millis", percentile(sorted, 0.50));
            out.put("p95Millis", percentile(sorted, 0.95));
            out.put("maxMillis", max);
            return out;
        }

        private static long percentile(long[] sorted, double rank) {
            return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, Math.ceil(rank * sorted.length) - 1)];
        }
    }
}
