package io.testforge.contract.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A first JSON Schema drafted from real payloads, so a contract starts from
 * what the producer sends instead of a blank file:
 *
 * <pre>{@code
 * SchemaContract draft = ContractDrafts.fromSamples("partner-event", samples);
 * Files.writeString(Path.of("src/test/resources/contracts/partner-event.schema.json"), draft.schemaJson());
 * }</pre>
 *
 * <p>A field is {@code required} when every sample that has its parent has
 * it; types are the union of what the samples hold ({@code integer} widens to
 * {@code number} when both appear, {@code null} is added when a sample sent
 * null). Shape only: no example values, enums or patterns are copied out of
 * the samples — they may carry real data, and a contract that pins one
 * sample's values fails on the next.
 *
 * <p>A draft is a starting point to review, tighten (enums, formats,
 * {@code additionalProperties}) and commit — never a contract regenerated on
 * every run, which would accept whatever drift it was meant to catch.
 */
public final class ContractDrafts {

    private static final ObjectMapper MAPPER = ContractMappers.strict();

    private ContractDrafts() {
    }

    public static SchemaContract fromSamples(String name, List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("A draft needs at least one sample");
        }
        List<JsonNode> nodes = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            try {
                nodes.add(MAPPER.readTree(samples.get(i)));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Sample " + (i + 1) + " of " + name + " is not JSON: "
                        + e.getOriginalMessage(), e);
            }
        }
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.put("title", name);
        schema.put("description", "Drafted by TestForge from " + nodes.size()
                + " samples: shape only. Review, tighten and commit.");
        schema.setAll(shape(nodes));
        try {
            return SchemaContract.of(name, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(schema));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the drafted schema", e);
        }
    }

    /** The schema of every value seen at one place across the samples. */
    private static ObjectNode shape(List<JsonNode> values) {
        Set<String> types = new TreeSet<>();
        List<JsonNode> objects = new ArrayList<>();
        List<JsonNode> arrayItems = new ArrayList<>();
        for (JsonNode value : values) {
            String type = type(value);
            types.add(type);
            if (value.isObject()) {
                objects.add(value);
            } else if (value.isArray()) {
                value.forEach(arrayItems::add);
            }
        }
        if (types.contains("integer") && types.contains("number")) {
            types.remove("integer");
        }
        ObjectNode schema = MAPPER.createObjectNode();
        if (types.size() == 1) {
            schema.put("type", types.iterator().next());
        } else {
            ArrayNode list = schema.putArray("type");
            types.forEach(list::add);
        }
        if (!objects.isEmpty()) {
            Map<String, List<JsonNode>> fields = new TreeMap<>();
            Map<String, Integer> presence = new TreeMap<>();
            for (JsonNode object : objects) {
                object.properties().forEach(entry -> {
                    fields.computeIfAbsent(entry.getKey(), ignored -> new ArrayList<>()).add(entry.getValue());
                    presence.merge(entry.getKey(), 1, Integer::sum);
                });
            }
            ObjectNode properties = schema.putObject("properties");
            fields.forEach((field, seen) -> properties.set(field, shape(seen)));
            List<String> required = presence.entrySet().stream()
                    .filter(entry -> entry.getValue() == objects.size()).map(Map.Entry::getKey).toList();
            if (!required.isEmpty()) {
                ArrayNode list = schema.putArray("required");
                required.forEach(list::add);
            }
        }
        if (types.contains("array") && !arrayItems.isEmpty()) {
            schema.set("items", shape(arrayItems));
        }
        return schema;
    }

    private static String type(JsonNode value) {
        if (value.isObject()) {
            return "object";
        }
        if (value.isArray()) {
            return "array";
        }
        if (value.isNull()) {
            return "null";
        }
        if (value.isBoolean()) {
            return "boolean";
        }
        if (value.isIntegralNumber()) {
            return "integer";
        }
        if (value.isNumber()) {
            return "number";
        }
        return "string";
    }
}
