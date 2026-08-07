package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.testforge.contract.json.SchemaContract;
import java.util.List;
import java.util.Set;

/**
 * Turns an OpenAPI response schema into a self-contained JSON Schema the
 * existing {@code JsonContractValidator} can run.
 *
 * <p>Two problems have to be solved, and both have cheap correct answers.
 *
 * <p><b>References.</b> Rather than inlining {@code $ref}s — which needs cycle
 * detection, and self-referencing schemas will find the bug — the whole
 * {@code components.schemas} object is carried along inside the wrapper. The
 * networknt engine resolves {@code #/components/schemas/X} against the document
 * root, so the pointers keep working untouched. The schema itself goes under a
 * single-element {@code allOf} because a root-level {@code $ref} with sibling
 * keys is ignored under Draft-07 sibling rules.
 *
 * <p><b>Dialect.</b> OpenAPI 3.0 is not JSON Schema. {@code nullable: true} is
 * the one that matters: left alone, every legitimately null field in every
 * response is reported as a type violation, and the drift report becomes
 * noise. 3.1 is JSON Schema 2020-12 and needs none of this, which is why the
 * {@code $schema} keyword is set explicitly from the document's version rather
 * than left for the engine to guess.
 */
public class OpenApiSchemaAdapter {

    private static final String DRAFT_07 = "http://json-schema.org/draft-07/schema#";
    private static final String DRAFT_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    /** Keywords whose value is a single nested schema. */
    private static final List<String> NESTED_SCHEMA = List.of("items", "not", "additionalProperties", "contains");

    /** Keywords whose value is an array of schemas. */
    private static final List<String> SCHEMA_ARRAYS = List.of("allOf", "anyOf", "oneOf", "prefixItems");

    /** Keywords whose value maps names to schemas. */
    private static final List<String> SCHEMA_MAPS = List.of("properties", "patternProperties", "$defs", "definitions");

    /** OpenAPI-only annotations that mean nothing to a validator and mislead in violation output. */
    private static final Set<String> DROPPED = Set.of("discriminator", "xml", "externalDocs");

    private final ObjectMapper objectMapper;

    public OpenApiSchemaAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SchemaContract toContract(OpenApiDocument document, JsonNode schema, String name) {
        boolean oas31 = document.oas31();

        ObjectNode root = objectMapper.createObjectNode();
        root.put("$schema", oas31 ? DRAFT_2020_12 : DRAFT_07);
        root.putArray("allOf").add(convert(schema, oas31));

        JsonNode schemas = document.components().path("schemas");
        if (schemas.isObject()) {
            ObjectNode components = root.putObject("components");
            components.set("schemas", convertMap(schemas, oas31));
        }

        return SchemaContract.of(name, root.toString());
    }

    private JsonNode convert(JsonNode schema, boolean oas31) {
        if (!schema.isObject()) {
            return schema.deepCopy();
        }

        ObjectNode converted = ((ObjectNode) schema).deepCopy();
        DROPPED.forEach(converted::remove);

        if (!oas31) {
            applyNullable(converted);
            applyExclusiveBounds(converted);
        }

        // recurse only into positions the specification defines as schemas, so
        // a property literally named "nullable" or "type" is never rewritten
        NESTED_SCHEMA.forEach(keyword -> {
            if (converted.path(keyword).isObject()) {
                converted.set(keyword, convert(converted.get(keyword), oas31));
            }
        });
        SCHEMA_ARRAYS.forEach(keyword -> {
            if (converted.path(keyword).isArray()) {
                ArrayNode branches = objectMapper.createArrayNode();
                converted.get(keyword).forEach(branch -> branches.add(convert(branch, oas31)));
                converted.set(keyword, branches);
            }
        });
        SCHEMA_MAPS.forEach(keyword -> {
            if (converted.path(keyword).isObject()) {
                converted.set(keyword, convertMap(converted.get(keyword), oas31));
            }
        });

        return converted;
    }

    private ObjectNode convertMap(JsonNode map, boolean oas31) {
        ObjectNode converted = objectMapper.createObjectNode();
        map.properties().forEach(entry -> converted.set(entry.getKey(), convert(entry.getValue(), oas31)));
        return converted;
    }

    /**
     * {@code nullable: true} becomes a union type — or, for a {@code $ref},
     * an {@code anyOf} with the null branch, since a sibling {@code type} next
     * to {@code $ref} would be ignored.
     */
    private void applyNullable(ObjectNode schema) {
        if (!schema.path("nullable").asBoolean(false)) {
            schema.remove("nullable");
            return;
        }
        schema.remove("nullable");

        JsonNode type = schema.path("type");
        if (type.isTextual()) {
            ArrayNode union = objectMapper.createArrayNode().add(type.asText()).add("null");
            schema.set("type", union);
            return;
        }
        if (type.isArray()) {
            ((ArrayNode) type).add("null");
            return;
        }
        if (schema.hasNonNull("$ref")) {
            ObjectNode reference = objectMapper.createObjectNode().put("$ref", schema.get("$ref").asText());
            schema.remove("$ref");
            schema.putArray("anyOf")
                    .add(reference)
                    .add(objectMapper.createObjectNode().put("type", "null"));
        }
    }

    /** OpenAPI 3.0 spells these as booleans next to minimum/maximum; Draft-07 wants numbers. */
    private void applyExclusiveBounds(ObjectNode schema) {
        rewriteBound(schema, "exclusiveMinimum", "minimum");
        rewriteBound(schema, "exclusiveMaximum", "maximum");
    }

    private void rewriteBound(ObjectNode schema, String exclusive, String inclusive) {
        JsonNode flag = schema.path(exclusive);
        if (!flag.isBoolean()) {
            return;
        }
        if (flag.asBoolean() && schema.path(inclusive).isNumber()) {
            schema.set(exclusive, schema.get(inclusive));
            schema.remove(inclusive);
        } else {
            schema.remove(exclusive);
        }
    }
}
