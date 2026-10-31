package io.testforge.contract.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What a new version of a schema means for the consumers built against the
 * old one — before the producer ships it:
 *
 * <pre>{@code
 * List<ContractChange> changes = ContractChanges.compare(
 *         SchemaContract.fromResource("partner-event", "contracts/partner-event.v1.schema.json"),
 *         SchemaContract.fromResource("partner-event", "contracts/partner-event.v2.schema.json"));
 * ContractChanges.assertNoBreaking(changes);
 * }</pre>
 *
 * <p>Judged from the consumer's side: a field that may now be missing, a type
 * that may now arrive, a value it has never seen. Compared are
 * {@code properties}, {@code required}, {@code type}, {@code enum},
 * {@code items} and {@code additionalProperties}; anything a schema expresses
 * through {@code $ref} or composition ({@code allOf}, {@code oneOf},
 * {@code anyOf}) is reported as {@code UNKNOWN} rather than guessed at.
 */
public final class ContractChanges {

    private static final ObjectMapper MAPPER = ContractMappers.strict();
    private static final List<String> OPAQUE = List.of("$ref", "allOf", "oneOf", "anyOf", "not");

    private ContractChanges() {
    }

    public static List<ContractChange> compare(SchemaContract before, SchemaContract after) {
        List<ContractChange> changes = new ArrayList<>();
        walk("$", read(before), read(after), changes);
        changes.sort(Comparator.comparing(ContractChange::impact).thenComparing(ContractChange::path));
        return List.copyOf(changes);
    }

    /** Fails with every breaking change; risky and unknown ones are listed alongside. */
    public static void assertNoBreaking(List<ContractChange> changes) {
        List<ContractChange> breaking = changes.stream()
                .filter(change -> change.impact() == ContractChange.Impact.BREAKING).toList();
        if (!breaking.isEmpty()) {
            throw new AssertionError(breaking.size() + " breaking contract changes:\n" + changes.stream()
                    .filter(change -> change.impact() != ContractChange.Impact.SAFE)
                    .map(change -> "  - " + change).collect(Collectors.joining("\n")));
        }
    }

    private static void walk(String path, JsonNode before, JsonNode after, List<ContractChange> changes) {
        for (String keyword : OPAQUE) {
            if (before.has(keyword) || after.has(keyword)) {
                if (!before.path(keyword).equals(after.path(keyword))) {
                    changes.add(change(path, ContractChange.Impact.UNKNOWN,
                            keyword + " changed; not compared, review by hand"));
                }
                return;
            }
        }
        compareTypes(path, before, after, changes);
        compareEnums(path, before, after, changes);
        compareProperties(path, before, after, changes);
        if (before.has("items") || after.has("items")) {
            walk(path + "[]", before.path("items"), after.path("items"), changes);
        }
    }

    private static void compareTypes(String path, JsonNode before, JsonNode after, List<ContractChange> changes) {
        Set<String> was = types(before);
        Set<String> now = types(after);
        if (was.isEmpty() || now.isEmpty() || was.equals(now)) {
            return;
        }
        Set<String> added = new TreeSet<>(now);
        added.removeAll(was);
        // an integer is still a number for a consumer that reads numbers
        if (was.contains("number")) {
            added.remove("integer");
        }
        if (added.isEmpty()) {
            changes.add(change(path, ContractChange.Impact.SAFE, "type narrowed " + was + " → " + now));
        } else {
            changes.add(change(path, ContractChange.Impact.BREAKING,
                    "may now arrive as " + added + " (was " + was + ")"));
        }
    }

    private static void compareEnums(String path, JsonNode before, JsonNode after, List<ContractChange> changes) {
        if (!before.has("enum") && !after.has("enum")) {
            return;
        }
        Set<String> was = values(before.path("enum"));
        Set<String> now = values(after.path("enum"));
        if (!after.has("enum")) {
            changes.add(change(path, ContractChange.Impact.RISKY, "enum removed: any value may now arrive"));
            return;
        }
        Set<String> added = new TreeSet<>(now);
        added.removeAll(was);
        Set<String> removed = new TreeSet<>(was);
        removed.removeAll(now);
        if (!added.isEmpty() && before.has("enum")) {
            changes.add(change(path, ContractChange.Impact.RISKY,
                    "new values " + added + ": a consumer switching over the old ones may not handle them"));
        }
        if (!removed.isEmpty() || !before.has("enum")) {
            changes.add(change(path, ContractChange.Impact.SAFE, before.has("enum")
                    ? "values no longer sent " + removed : "values restricted to " + now));
        }
    }

    private static void compareProperties(String path, JsonNode before, JsonNode after, List<ContractChange> changes) {
        JsonNode was = before.path("properties");
        JsonNode now = after.path("properties");
        Set<String> wasRequired = values(before.path("required"));
        Set<String> nowRequired = values(after.path("required"));
        Set<String> fields = new TreeSet<>();
        was.fieldNames().forEachRemaining(fields::add);
        now.fieldNames().forEachRemaining(fields::add);
        for (String field : fields) {
            String child = path + "." + field;
            if (!now.has(field)) {
                changes.add(change(child, wasRequired.contains(field) ? ContractChange.Impact.BREAKING
                        : ContractChange.Impact.RISKY, wasRequired.contains(field)
                        ? "required field removed" : "optional field removed: consumers reading it get nothing"));
                continue;
            }
            if (!was.has(field)) {
                boolean strict = before.path("additionalProperties").isBoolean()
                        && !before.path("additionalProperties").asBoolean();
                changes.add(change(child, strict ? ContractChange.Impact.RISKY : ContractChange.Impact.SAFE,
                        strict ? "new field where the old schema allowed no additional properties"
                                : "new field" + (nowRequired.contains(field) ? " (required)" : "")));
                continue;
            }
            if (wasRequired.contains(field) && !nowRequired.contains(field)) {
                changes.add(change(child, ContractChange.Impact.BREAKING, "no longer required: may be missing"));
            } else if (!wasRequired.contains(field) && nowRequired.contains(field)) {
                changes.add(change(child, ContractChange.Impact.SAFE, "now always sent"));
            }
            walk(child, was.path(field), now.path(field), changes);
        }
    }

    private static Set<String> types(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            return Set.of(type.asText());
        }
        return values(type);
    }

    private static Set<String> values(JsonNode array) {
        Set<String> values = new TreeSet<>();
        if (array.isArray()) {
            array.forEach(value -> values.add(value.isTextual() ? value.asText() : value.toString()));
        }
        return values;
    }

    private static ContractChange change(String path, ContractChange.Impact impact, String detail) {
        return new ContractChange(path, impact, detail);
    }

    private static JsonNode read(SchemaContract contract) {
        try {
            return MAPPER.readTree(contract.schemaJson());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Schema of " + contract.name() + " is not JSON: "
                    + e.getOriginalMessage(), e);
        }
    }
}
