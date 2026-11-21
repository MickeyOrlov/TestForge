package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.testforge.api.explorer.PreparedRequest;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

/** Bounded response aggregation; cursors are opaque query data, never navigation targets. */
final class BusinessResponseReader {
    @FunctionalInterface
    interface Sender {
        HttpResponse<byte[]> send(PreparedRequest request) throws Exception;
    }

    private final ObjectMapper mapper;
    private final int maxBytes;

    BusinessResponseReader(ObjectMapper mapper, int maxBytes) {
        this.mapper = mapper;
        this.maxBytes = maxBytes;
    }

    JsonNode read(
            BusinessSecuritySuite.Step step,
            PreparedRequest first,
            Sender sender,
            Runnable unexpectedSuccess,
            Predicate<JsonNode> pageChecks)
            throws Exception {
        var pagination = step.pagination();
        var aggregate = mapper.createArrayNode();
        var cursors = new HashSet<String>();
        if (pagination != null && first.queryParameters().containsKey(pagination.parameter()))
            cursors.add(first.queryParameters().get(pagination.parameter()));
        PreparedRequest request = first;
        long bytes = 2;
        for (int page = 1; ; page++) {
            var response = sender.send(request);
            int status = response.statusCode();
            if (status == 429 || status >= 500) return null;
            if (!step.statuses().contains(status)) {
                if (step.phase() != BusinessSecuritySuite.Phase.PROBE
                        || status < 200
                        || status >= 300) return null;
                unexpectedSuccess.run();
            }
            if (pagination == null
                    && step.assertions().isEmpty()
                    && step.captures().isEmpty()
                    && step.invariants().isEmpty()) return NullNode.instance;
            JsonNode body = mapper.readTree(response.body());
            if (body == null) return null;
            if (pagination == null) return body;
            JsonNode items = body.at(pagination.items());
            if (!items.isArray()) return null;
            if (!pageChecks.test(body)) return null;
            if (items.size() > pagination.maxItems() - aggregate.size()) return null;
            for (JsonNode item : items) {
                bytes += item.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                if (bytes > maxBytes) return null;
                aggregate.add(item);
            }
            JsonNode next = body.at(pagination.next());
            if (next.isMissingNode()) return null;
            if (next.isNull() || next.isTextual() && next.textValue().isEmpty()) {
                if (pagination.requireItems() && aggregate.isEmpty()) return null;
                String pointer = pagination.items();
                int slash = pointer.lastIndexOf('/');
                JsonNode parent = body.at(pointer.substring(0, slash));
                if (!(parent instanceof ObjectNode object)) return null;
                object.set(pointer.substring(slash + 1), aggregate);
                return body.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes
                        ? null
                        : body;
            }
            if (!(next.isTextual() || next.isIntegralNumber()) || page >= pagination.maxPages())
                return null;
            String cursor = next.asText();
            if (cursor.length() > 512
                    || !cursor.matches("[a-zA-Z0-9._~+/=-]+")
                    || !cursors.add(cursor)) return null;
            Map<String, String> query = new TreeMap<>(first.queryParameters());
            query.put(pagination.parameter(), cursor);
            request =
                    new PreparedRequest(
                            first.method(),
                            first.pathTemplate(),
                            first.pathParameters(),
                            query,
                            null,
                            null);
        }
    }
}
