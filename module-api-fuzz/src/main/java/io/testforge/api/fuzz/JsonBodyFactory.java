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
 * generate will match, a {@code oneOf} nothing pins to one branch — the
 * operation is skipped with a reason instead of being sent a plausible-looking
 * guess.
 *
 * <p>Generation is deterministic. The same document produces the same baseline,
 * so the same case produces the same request on every run.
 */
public class JsonBodyFactory {

    private static final int MAX_DEPTH = 8;

    private final ObjectMapper objectMapper;
    private final Compositions compositions = new Compositions(this::effective);

    public JsonBodyFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A body the schema would accept, or the reason none could be built.
     *
     * <p>{@code unfuzzablePaths} are places no mutation can be defended —
     * unpinned composition branches, and the discriminator property of a pinned
     * one. {@code unsupported} carries the same information as reportable
     * constraints, so the coverage report can say what was skipped and why
     * rather than silently listing fewer promises.
     */
    public record Baseline(JsonNode body, String unsupportedReason, Set<String> unfuzzablePaths,
                           List<UnsupportedConstraint> unsupported) {

        public Baseline {
            unfuzzablePaths = Set.copyOf(unfuzzablePaths == null ? Set.of() : unfuzzablePaths);
            unsupported = List.copyOf(unsupported == null ? List.of() : unsupported);
        }

        static Baseline of(JsonNode body, Set<String> unfuzzablePaths, List<UnsupportedConstraint> unsupported) {
            return new Baseline(body, null, unfuzzablePaths, unsupported);
        }

        static Baseline unsupported(String reason) {
            return new Baseline(null, reason, Set.of(), List.of());
        }

        public boolean usable() {
            return body != null;
        }
    }

    public Baseline build(Schema<?> schema) {
        if (schema == null) {
            return Baseline.unsupported("the request body declares no schema");
        }

        Limits limits = new Limits();
        try {
            JsonNode body = value(schema, "$", limits, 0);
            return body == null
                    ? Baseline.unsupported("no value satisfying the request body schema could be built")
                    : Baseline.of(body, limits.unfuzzablePaths, limits.unsupported);
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
        // additionalProperties is deliberately not carried across an allOf. Each
        // subschema sees only its own properties, so a merged "false" would
        // forbid fields a sibling declares and manufacture findings out of a
        // JSON Schema subtlety rather than out of the service's behaviour
        return merged;
    }

    private JsonNode value(Schema<?> raw, String path, Limits limits, int depth) {
        if (depth > MAX_DEPTH) {
            throw new UnbuildableException("schema nests deeper than " + MAX_DEPTH + " levels at " + path);
        }

        Schema<?> schema = raw;
        if (Compositions.branching(schema)) {
            Compositions.Choice choice = compositions.choose(schema);
            if (choice.fuzzable()) {
                // the discriminator pins the branch, so the rest of it is fair
                // game — but the discriminator itself is not: changing it would
                // hand the request to a schema the case was never derived from
                limits.unfuzzablePaths.add(BodyPaths.child(path, choice.discriminatorProperty()));
            } else {
                limits.unfuzzablePaths.add(path);
                limits.unsupported.add(new UnsupportedConstraint(path, Compositions.keyword(schema),
                        choice.unsupportedReason()));
            }
            if (path.equals("$") && !choice.fuzzable()) {
                // nothing at all could be fuzzed in this body, and a control
                // request alone is not what the operation was selected for
                throw new UnbuildableException(choice.unsupportedReason());
            }
            schema = choice.branch();
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
            case "object" -> object(schema, path, limits, depth);
            case "array" -> array(schema, path, limits, depth);
            case "integer", "number" -> number(schema, path);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(true);
            case "null" -> objectMapper.getNodeFactory().nullNode();
            default -> objectMapper.getNodeFactory().textNode(string(schema, path));
        };
    }

    private JsonNode object(Schema<?> schema, String path, Limits limits, int depth) {
        ObjectNode node = objectMapper.createObjectNode();
        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return node;
        }

        // every buildable property is included, not only the required ones:
        // an optional field absent from the baseline could never be fuzzed
        Map<String, Schema> ordered = new LinkedHashMap<>(properties);
        for (Map.Entry<String, Schema> property : ordered.entrySet()) {
            String childPath = BodyPaths.child(path, property.getKey());

            if (SchemaFacts.readOnly(property.getValue())) {
                if (required(schema, property.getKey())) {
                    // OpenAPI resolves this itself — a readOnly property in
                    // required applies to responses only — but a document that
                    // says both leaves no request this module can call correct
                    limits.unsupported.add(new UnsupportedConstraint(childPath, "readOnly",
                            "the property is both required and readOnly, so no request can satisfy the document "
                                    + "as written; it is left out of the control"));
                }
                // a control that sends response-only fields is not the valid
                // request the document describes
                continue;
            }

            JsonNode child = value(property.getValue(), childPath, limits, depth + 1);
            if (child != null) {
                node.set(property.getKey(), child);
            } else if (required(schema, property.getKey())) {
                throw new UnbuildableException("no valid value could be built for required field " + childPath);
            }
        }
        return node;
    }

    private JsonNode array(Schema<?> schema, String path, Limits limits, int depth) {
        ArrayNode node = objectMapper.createArrayNode();
        int size = Math.max(schema.getMinItems() == null ? 1 : schema.getMinItems(), 1);
        if (schema.getMaxItems() != null) {
            size = Math.min(size, schema.getMaxItems());
        }

        Schema<?> items = schema.getItems();
        if (items == null) {
            return node;
        }
        JsonNode element = value(items, BodyPaths.element(path, 0), limits, depth + 1);
        if (element == null) {
            throw new UnbuildableException("no valid element could be built for array " + path);
        }

        if (!SchemaFacts.uniqueItems(schema)) {
            for (int index = 0; index < size; index++) {
                node.add(element.deepCopy());
            }
            return node;
        }

        // uniqueItems and a minItems above one is the case v1.3 got wrong: the
        // baseline filled the array with copies, so the control itself violated
        // the document and every case under it meant nothing
        Set<String> seen = new LinkedHashSet<>();
        node.add(element.deepCopy());
        seen.add(element.toString());
        for (int index = 1; index < size; index++) {
            JsonNode distinct = distinct(items, element, index, seen);
            if (distinct == null) {
                throw new UnbuildableException(
                        "uniqueItems requires " + size + " different elements at " + path
                                + ", and the item schema does not allow that many");
            }
            node.add(distinct);
            seen.add(distinct.toString());
        }
        return node;
    }

    /** A second, third, nth element that differs from the ones already placed. */
    private JsonNode distinct(Schema<?> raw, JsonNode first, int index, Set<String> seen) {
        Schema<?> items = effective(raw);

        Optional<List<String>> enumValues = SchemaFacts.enumValues(items);
        if (enumValues.isPresent()) {
            return enumValues.get().stream()
                    .map(value -> (JsonNode) objectMapper.getNodeFactory().textNode(value))
                    .filter(candidate -> !seen.contains(candidate.toString()))
                    .findFirst()
                    .orElse(null);
        }

        String type = Schemas.type(items);
        if ("boolean".equals(type)) {
            JsonNode candidate = objectMapper.getNodeFactory().booleanNode(false);
            return seen.contains(candidate.toString()) ? null : candidate;
        }
        if ("integer".equals(type) || "number".equals(type)) {
            return distinctNumber(items, first, index, seen);
        }
        if (type == null || "string".equals(type)) {
            return distinctString(items, first, index, seen);
        }
        // objects and nested arrays: varying one leaf would need a second copy
        // of the whole generator, and getting it wrong means an invalid control
        return null;
    }

    private JsonNode distinctNumber(Schema<?> items, JsonNode first, int index, Set<String> seen) {
        BigDecimal step = SchemaFacts.multipleOf(items).orElse(BigDecimal.ONE);
        BigDecimal base = first.decimalValue();
        for (int attempt = 1; attempt <= index + 4; attempt++) {
            BigDecimal candidate = base.add(step.multiply(BigDecimal.valueOf(attempt)));
            if (!SchemaFacts.satisfiesNumeric(items, candidate)) {
                continue;
            }
            JsonNode node = SchemaFacts.integer(items)
                    ? objectMapper.getNodeFactory().numberNode(candidate.longValueExact())
                    : objectMapper.getNodeFactory().numberNode(candidate);
            if (!seen.contains(node.toString())) {
                return node;
            }
        }
        return null;
    }

    private JsonNode distinctString(Schema<?> items, JsonNode first, int index, Set<String> seen) {
        String base = first.asText();
        List<String> candidates = new ArrayList<>();
        // vary in place first: same length keeps minLength, maxLength and most
        // patterns satisfied, which appending would not
        if (!base.isEmpty()) {
            char replacement = (char) ('a' + ((base.charAt(base.length() - 1) - 'a' + index) % 26));
            candidates.add(base.substring(0, base.length() - 1) + replacement);
        }
        candidates.add(base + index);
        candidates.add("a".repeat(index + 1));

        for (String candidate : candidates) {
            if (!SchemaFacts.satisfiesString(items, candidate)) {
                continue;
            }
            JsonNode node = objectMapper.getNodeFactory().textNode(candidate);
            if (!seen.contains(node.toString())) {
                return node;
            }
        }
        return null;
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

    /** What the walk learned it may not touch, collected as it goes. */
    private static final class Limits {
        private final Set<String> unfuzzablePaths = new LinkedHashSet<>();
        private final List<UnsupportedConstraint> unsupported = new ArrayList<>();
    }

    /** Signals that no valid body exists for this schema, with the reason to report. */
    private static final class UnbuildableException extends RuntimeException {
        UnbuildableException(String message) {
            super(message);
        }
    }
}
