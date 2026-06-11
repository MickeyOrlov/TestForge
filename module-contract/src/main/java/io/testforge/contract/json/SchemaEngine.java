package io.testforge.contract.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.SpecVersionDetector;
import com.networknt.schema.ValidationMessage;
import io.testforge.contract.ContractProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * networknt-backed engine for {@link SchemaContract}s.
 *
 * <p>Violation codes are schema keywords ({@code required}, {@code type},
 * {@code enum}, ...). Violations are ordered by a fixed keyword priority so
 * the first reported reason is the most actionable one and assertion output
 * stays stable across library versions; composite keywords ({@code oneOf},
 * {@code anyOf}, ...) sort last because they restate their children.
 */
final class SchemaEngine {

    private static final List<String> KEYWORD_PRIORITY = List.of(
            "required", "type", "format", "enum", "const", "pattern",
            "minimum", "maximum", "minLength", "maxLength", "additionalProperties");

    private static final Set<String> COMPOSITE_KEYWORDS = Set.of("oneOf", "anyOf", "allOf", "not");

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, JsonSchema> compiledSchemas = new ConcurrentHashMap<>();

    SchemaEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<ContractViolation> validate(JsonNode root, SchemaContract contract, ContractProperties properties) {
        JsonSchema schema = compiledSchemas.computeIfAbsent(contract.schemaJson(), this::compile);

        Set<ValidationMessage> messages = schema.validate(root);

        List<ContractViolation> violations = new ArrayList<>(messages.size());
        for (ValidationMessage message : messages) {
            violations.add(new ContractViolation(
                    contract.name(),
                    violationPath(message),
                    message.getType(),
                    message.getMessage()));
        }

        violations.sort(Comparator
                .comparingInt((ContractViolation violation) -> keywordRank(violation.code()))
                .thenComparing(ContractViolation::path));

        int limit = properties.failFast() ? 1 : properties.maxViolations();
        return violations.size() <= limit
                ? List.copyOf(violations)
                : List.copyOf(violations.subList(0, limit));
    }

    private JsonSchema compile(String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            SpecVersion.VersionFlag version = schemaNode.hasNonNull("$schema")
                    ? SpecVersionDetector.detect(schemaNode)
                    : SpecVersion.VersionFlag.V7;
            return JsonSchemaFactory.getInstance(version).getSchema(schemaNode);
        } catch (Exception e) {
            throw new IllegalArgumentException("Schema contract is not a valid JSON Schema", e);
        }
    }

    /** For {@code required} the instance location is the parent — append the missing property. */
    private static String violationPath(ValidationMessage message) {
        String location = String.valueOf(message.getInstanceLocation());
        if ("required".equals(message.getType()) && message.getProperty() != null) {
            return location + "." + message.getProperty();
        }
        return location;
    }

    private static int keywordRank(String keyword) {
        int index = KEYWORD_PRIORITY.indexOf(keyword);
        if (index >= 0) {
            return index;
        }
        return COMPOSITE_KEYWORDS.contains(keyword)
                ? KEYWORD_PRIORITY.size() + 100
                : KEYWORD_PRIORITY.size();
    }
}
