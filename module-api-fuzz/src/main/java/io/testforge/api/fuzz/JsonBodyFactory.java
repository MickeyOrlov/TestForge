package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds the valid request body every body case starts from.
 *
 * <p>The baseline is the load-bearing part of body fuzzing. A case says "this
 * one field is wrong"; that claim is only true if everything else in the
 * document was right. So the baseline is built to satisfy every constraint the
 * schema declares, and when it cannot be — a pattern nothing this module can
 * generate will match, a {@code oneOf} at the root — the operation is skipped
 * with a reason instead of being sent a plausible-looking guess.
 *
 * <p>Generation is deterministic. The same document produces the same baseline,
 * so the same case produces the same request on every run.
 */
public class JsonBodyFactory {

    private static final int MAX_DEPTH = 8;

    private final ObjectMapper objectMapper;

    public JsonBodyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A body the schema would accept, or the reason none could be built.
     *
     * <p>{@code unfuzzablePaths} are places the baseline had to choose a branch
     * of a {@code oneOf}/{@code anyOf}. A value invalid for the chosen branch
     * may be valid for another, so no {@code REJECT} can be proven there and no
     * case is generated.
     */
    public record Baseline(JsonNode body, String unsupportedReason, Set<String> unfuzzablePaths) {

        public Baseline {
            unfuzzablePaths = Set.copyOf(unfuzzablePaths == null ? Set.of() : unfuzzablePaths);
        }

        static Baseline of(JsonNode body, Set<String> unfuzzablePaths) {
            return new Baseline(body, null, unfuzzablePaths);
        }

        static Baseline unsupported(String reason) {
            return new Baseline(null, reason, Set.of());
        }

        public boolean usable() {
            return body != null;
        }
    }

    public Baseline build(Schema<?> schema) {
        if (schema == null) {
            return Baseline.unsupported("the request body declares no schema");
        }
        if (branching(schema)) {
            return Baseline.unsupported(
                    "the request body schema is a oneOf/anyOf; no single valid baseline can be proven");
        }

        Set<String> unfuzzable = new LinkedHashSet<>();
        try {
            JsonNode body = value(schema, "$", unfuzzable, 0);
            return body == null
                    ? Baseline.unsupported("no value satisfying the request body schema could be built")
                    : Baseline.of(body, unfuzzable);
        } catch (UnbuildableException e) {
            return Baseline.unsupported(e.getMessage());
        }
    }

    /** Effective schema after merging {@code allOf}, which real documents use constantly. */
    Schema<?> effective(Schema<?> schema) {
        if (schema == null || schema.getAllOf() == null || schema.getAllOf().isEmpty()) {
            return schema;
        }

        Schema<Object> merged = new Schema<>();
        merged.setType("object");
        Map<String, Schema> properties = new TreeMap<>();
        List<String> required = new ArrayList<>();

        for (Schema<?> part : schema.getAllOf()) {
            Schema<?> resolved = effective(part);
            if (resolved == null) {
                continue;
            }
            if (resolved.getProperties() != null) {
                properties.putAll(resolved.getProperties());
            }
            if (resolved.getRequired() != null) {
                resolved.getRequired().forEach(name -> {
                    if (!required.contains(name)) {
                        required.add(name);
                    }
                });
            }
        }
        if (schema.getProperties() != null) {
            properties.putAll(schema.getProperties());
        }
        if (schema.getRequired() != null) {
            schema.getRequired().forEach(name -> {
                if (!required.contains(name)) {
                    required.add(name);
                }
            });
        }

        merged.setProperties(properties);
        merged.setRequired(required);
        return merged;
    }

    private boolean branching(Schema<?> schema) {
        return (schema.getOneOf() != null && !schema.getOneOf().isEmpty())
                || (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty());
    }

    private JsonNode value(Schema<?> raw, String path, Set<String> unfuzzable, int depth) {
        if (depth > MAX_DEPTH) {
            throw new UnbuildableException("schema nests deeper than " + MAX_DEPTH + " levels at " + path);
        }

        Schema<?> schema = raw;
        if (branching(schema)) {
            // one branch is enough for a valid body, but nothing inside it can
            // be proven invalid afterwards
            unfuzzable.add(path);
            List<Schema> branches = schema.getOneOf() != null && !schema.getOneOf().isEmpty()
                    ? schema.getOneOf()
                    : schema.getAnyOf();
            schema = branches.getFirst();
        }
        schema = effective(schema);
        if (schema == null) {
            return null;
        }

        Optional<List<String>> enumValues = SchemaFacts.enumValues(schema);
        if (enumValues.isPresent()) {
            return objectMapper.getNodeFactory().textNode(enumValues.get().getFirst());
        }

        String type = Schemas.type(schema);
        if (type == null) {
            type = schema.getProperties() != null ? "object" : "string";
        }

        return switch (type) {
            case "object" -> object(schema, path, unfuzzable, depth);
            case "array" -> array(schema, path, unfuzzable, depth);
            case "integer", "number" -> number(schema, path);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(true);
            case "null" -> objectMapper.getNodeFactory().nullNode();
            default -> objectMapper.getNodeFactory().textNode(string(schema, path));
        };
    }

    private JsonNode object(Schema<?> schema, String path, Set<String> unfuzzable, int depth) {
        ObjectNode node = objectMapper.createObjectNode();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return node;
        }

        // every buildable property is included, not only the required ones:
        // an optional field absent from the baseline could never be fuzzed
        Map<String, Schema> ordered = new LinkedHashMap<>(properties);
        for (Map.Entry<String, Schema> property : ordered.entrySet()) {
            JsonNode child = value(property.getValue(), BodyPaths.child(path, property.getKey()),
                    unfuzzable, depth + 1);
            if (child != null) {
                node.set(property.getKey(), child);
            } else if (required(schema, property.getKey())) {
                throw new UnbuildableException(
                        "no valid value could be built for required field " + BodyPaths.child(path, property.getKey()));
            }
        }
        return node;
    }

    private JsonNode array(Schema<?> schema, String path, Set<String> unfuzzable, int depth) {
        ArrayNode node = objectMapper.createArrayNode();
        int size = Math.max(schema.getMinItems() == null ? 1 : schema.getMinItems(), 1);
        if (schema.getMaxItems() != null) {
            size = Math.min(size, schema.getMaxItems());
        }

        Schema<?> items = schema.getItems();
        if (items == null) {
            return node;
        }
        JsonNode element = value(items, BodyPaths.element(path, 0), unfuzzable, depth + 1);
        if (element == null) {
            throw new UnbuildableException("no valid element could be built for array " + path);
        }
        for (int index = 0; index < size; index++) {
            node.add(element.deepCopy());
        }
        return node;
    }

    private JsonNode number(Schema<?> schema, String path) {
        BigDecimal candidate = SchemaFacts.inclusiveMinimum(schema)
                .or(() -> SchemaFacts.exclusiveMinimum(schema).map(bound -> bound.add(BigDecimal.ONE)))
                .or(() -> SchemaFacts.multipleOf(schema))
                .orElse(BigDecimal.ONE);

        // nudge onto a multiple, then verify rather than assume
        Optional<BigDecimal> factor = SchemaFacts.multipleOf(schema);
        if (factor.isPresent() && factor.get().signum() != 0
                && candidate.remainder(factor.get()).compareTo(BigDecimal.ZERO) != 0) {
            candidate = candidate.divide(factor.get(), 0, java.math.RoundingMode.CEILING).multiply(factor.get());
        }
        if (!SchemaFacts.satisfiesNumeric(schema, candidate)) {
            throw new UnbuildableException("no number satisfies every declared constraint at " + path);
        }

        return SchemaFacts.integer(schema)
                ? objectMapper.getNodeFactory().numberNode(candidate.longValueExact())
                : objectMapper.getNodeFactory().numberNode(candidate);
    }

    private String string(Schema<?> schema, String path) {
        String format = Schemas.format(schema);
        List<String> candidates = new ArrayList<>();
        if (format != null) {
            candidates.add(formatSample(format));
        }
        int minLength = schema != null && schema.getMinLength() != null ? schema.getMinLength() : 4;
        candidates.add("a".repeat(Math.max(minLength, 1)));
        candidates.add("testforge");

        for (String candidate : candidates) {
            if (SchemaFacts.satisfiesString(schema, candidate)) {
                return candidate;
            }
        }
        throw new UnbuildableException(
                "no string satisfies the declared pattern/length at " + path
                        + " (pattern: " + (schema == null ? null : schema.getPattern()) + ")");
    }

    private String formatSample(String format) {
        return switch (format) {
            case "uuid" -> "00000000-0000-0000-0000-000000000001";
            case "date" -> "2024-01-01";
            case "date-time" -> "2024-01-01T00:00:00Z";
            case "email" -> "explorer@testforge.invalid";
            case "uri", "url" -> "https://testforge.invalid";
            case "byte" -> "dGVzdGZvcmdl";
            case "ipv4" -> "192.0.2.1";
            case "ipv6" -> "2001:db8::1";
            default -> "testforge";
        };
    }

    private boolean required(Schema<?> schema, String property) {
        return schema.getRequired() != null && schema.getRequired().contains(property);
    }

    /** Signals that no valid body exists for this schema, with the reason to report. */
    private static final class UnbuildableException extends RuntimeException {
        UnbuildableException(String message) {
            super(message);
        }
    }
}
