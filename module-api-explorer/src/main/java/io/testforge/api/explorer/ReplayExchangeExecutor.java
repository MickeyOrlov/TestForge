package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Answers requests from a previous run's observations instead of the network,
 * so a failing chain can be reproduced — and debugged — without the
 * environment. Enabled by {@code forge.api-explorer.replay-from}.
 *
 * <p>A request matches a recording by method plus resolved path and query.
 * What the recording cannot faithfully give back is reported, not papered
 * over: an unrecorded request, a recorded failure, and a response body that
 * was truncated at {@code max-body-chars} all come back as failed exchanges
 * with the reason. Redacted values stay redacted ({@code ***}).
 */
public class ReplayExchangeExecutor implements ExchangeExecutor {

    static final String TRUNCATION_MARKER = "... <truncated, ";

    private final Path recording;
    private final Map<String, JsonNode> exchanges;

    public ReplayExchangeExecutor(Path recording, Path outputDir) {
        Path source = recording.toAbsolutePath().normalize();
        if (source.equals(outputDir.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException(
                    "forge.api-explorer.replay-from must differ from output-dir: replaying would overwrite the recording");
        }
        this.recording = recording;
        this.exchanges = load(source);
        if (exchanges.isEmpty()) {
            throw new IllegalArgumentException("No recorded observations under " + recording
                    + " (expected <spec>/observations/*.json from an earlier run)");
        }
    }

    @Override
    public String baseUrl() {
        return "replay:" + recording;
    }

    @Override
    public RuntimeExchange execute(PreparedRequest request) {
        String key = request.method() + " " + request.resolvedTarget();
        JsonNode recorded = exchanges.get(key);
        if (recorded == null) {
            return RuntimeExchange.failed(Map.of(), "no recorded exchange for " + key, 0);
        }
        if (recorded.path("status").isNull() || recorded.path("status").isMissingNode()) {
            return RuntimeExchange.failed(Map.of(),
                    "the recorded call failed: " + recorded.path("reason").asText("unknown"), 0);
        }
        String body = recorded.path("responseBody").isNull() ? null : recorded.path("responseBody").asText(null);
        if (body != null && body.contains(TRUNCATION_MARKER)) {
            return RuntimeExchange.failed(Map.of(), "the recorded response body was truncated; raise "
                    + "forge.api-explorer.max-body-chars and record again", 0);
        }
        Map<String, String> headers = new LinkedHashMap<>();
        recorded.path("responseHeaders").properties().forEach(entry -> headers.put(entry.getKey(),
                entry.getValue().asText()));
        return new RuntimeExchange(Map.of(), request.body(), recorded.get("status").asInt(),
                recorded.path("contentType").isNull() ? null : recorded.path("contentType").asText(null),
                headers, body, recorded.path("durationMillis").asLong(0), null);
    }

    private static Map<String, JsonNode> load(Path source) {
        Map<String, JsonNode> loaded = new LinkedHashMap<>();
        if (!Files.isDirectory(source)) {
            return loaded;
        }
        ObjectMapper mapper = new ObjectMapper();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(source, 3)) {
            files = walk.filter(path -> path.toString().endsWith(".json"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals("observations"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the recording in " + source, e);
        }
        for (Path file : files) {
            try {
                JsonNode observation = mapper.readTree(file.toFile());
                if ("SKIPPED".equals(observation.path("outcome").asText())) {
                    continue;
                }
                // the first recording of a request wins, in sorted file order
                loaded.putIfAbsent(observation.path("method").asText() + " "
                        + target(observation.path("resolvedUrl").asText()), observation);
            } catch (IOException e) {
                throw new UncheckedIOException("Unreadable recorded observation " + file, e);
            }
        }
        return loaded;
    }

    /** {@code http://host:8080/tasks/1?x=2} → {@code /tasks/1?x=2}; the base URL is not part of identity. */
    static String target(String resolvedUrl) {
        int scheme = resolvedUrl.indexOf("://");
        if (scheme < 0) {
            return resolvedUrl;
        }
        int path = resolvedUrl.indexOf('/', scheme + 3);
        return path < 0 ? "/" : resolvedUrl.substring(path);
    }
}
