package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compares a real response with the one its own document promises.
 *
 * <p>Five questions, chosen because they are the ones that actually differ in
 * practice: is this status documented at all, is the media type one of the
 * declared ones, are the required fields there, are there fields nobody
 * declared, and does every value have a type the schema allows.
 *
 * <p>Written against the OpenAPI model rather than delegating to
 * {@code module-contract}'s JSON Schema engine on purpose. Two of these
 * findings are not JSON Schema questions: an undocumented status lives above
 * the schema, and an undeclared field is invisible to a validator because real
 * documents virtually never set {@code additionalProperties: false}. Reaching
 * for the engine would also mean converting OpenAPI's dialect first.
 * {@code module-contract} remains the right tool when a project wants full
 * schema validation of a specific payload; this is the breadth-first pass.
 */
public class ResponseContractChecker {

    private final ObjectMapper objectMapper;

    public ResponseContractChecker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<ContractMismatch> check(ExplorableOperation operation, RuntimeExchange exchange) {
        List<ContractMismatch> mismatches = new ArrayList<>();

        ApiResponse declared = declaredResponse(operation.operation().getResponses(), exchange.status());
        if (declared == null) {
            mismatches.add(ContractMismatch.response(MismatchKind.UNDOCUMENTED_STATUS,
                    "status %d is not declared for %s".formatted(exchange.status(), operation.key())));
            return List.copyOf(mismatches);
        }

        Map<String, MediaType> content = declared.getContent();
        if (content == null || content.isEmpty()) {
            // a documented status with no body declaration — nothing further to check
            return List.copyOf(mismatches);
        }

        MediaType mediaType = matchingMediaType(content, exchange.contentType());
        if (mediaType == null) {
            mismatches.add(ContractMismatch.response(MismatchKind.UNEXPECTED_CONTENT_TYPE,
                    "got %s, document declares %s".formatted(
                            exchange.contentType() == null ? "no content type" : exchange.contentType(),
                            String.join(", ", content.keySet()))));
            return List.copyOf(mismatches);
        }

        Schema<?> schema = mediaType.getSchema();
        if (schema == null || !isJson(exchange.contentType())) {
            return List.copyOf(mismatches);
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(exchange.responseBody() == null ? "" : exchange.responseBody());
        } catch (Exception e) {
            mismatches.add(ContractMismatch.response(MismatchKind.MALFORMED_BODY,
                    "declared as JSON but could not be parsed: " + e.getMessage()));
            return List.copyOf(mismatches);
        }
        if (body == null || body.isMissingNode()) {
            return List.copyOf(mismatches);
        }

        walk(schema, body, "$", mismatches, new LinkedHashSet<>(), 0);
        return List.copyOf(mismatches);
    }

    /** Exact status first, then the document's {@code default} response. */
    private ApiResponse declaredResponse(ApiResponses responses, int status) {
        if (responses == null) {
            return null;
        }
        ApiResponse exact = responses.get(String.valueOf(status));
        if (exact != null) {
            return exact;
        }
        // 2XX-style wildcards are legal in OpenAPI 3.1 and appear in the wild
        ApiResponse wildcard = responses.get(status / 100 + "XX");
        return wildcard != null ? wildcard : responses.get("default");
    }

    private MediaType matchingMediaType(Map<String, MediaType> content, String contentType) {
        if (contentType == null) {
            return null;
        }
        String base = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, MediaType> entry : content.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(base)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private boolean isJson(String contentType) {
        return contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    private void walk(Schema<?> schema, JsonNode node, String path,
                      List<ContractMismatch> mismatches, Set<String> reported, int depth) {

        if (schema == null || depth > 20) {
            return;
        }
        if (node.isNull()) {
            if (!Schemas.nullable(schema)) {
                report(mismatches, reported, ContractMismatch.at(MismatchKind.INCOMPATIBLE_FIELD_TYPE, path,
                        "null is not permitted here"));
            }
            return;
        }

        String type = Schemas.type(schema);
        if (type != null && !compatible(type, node)) {
            report(mismatches, reported, ContractMismatch.at(MismatchKind.INCOMPATIBLE_FIELD_TYPE, path,
                    "declared %s, got %s".formatted(type, node.getNodeType().name().toLowerCase(Locale.ROOT))));
            return;
        }

        if (node.isArray()) {
            Schema<?> items = schema.getItems();
            // every element is checked, but findings collapse onto one path:
            // a hundred bad elements are one defect, not a hundred report lines
            node.forEach(element -> walk(items, element, path + "[]", mismatches, reported, depth + 1));
            return;
        }
        if (node.isObject()) {
            walkObject(schema, node, path, mismatches, reported, depth);
        }
    }

    private void walkObject(Schema<?> schema, JsonNode node, String path,
                            List<ContractMismatch> mismatches, Set<String> reported, int depth) {

        Map<String, Schema> properties = schema.getProperties();
        if (properties == null || properties.isEmpty()) {
            return;
        }

        List<String> required = schema.getRequired() == null ? List.of() : schema.getRequired();
        for (String name : required) {
            if (!node.has(name)) {
                report(mismatches, reported, ContractMismatch.at(MismatchKind.MISSING_REQUIRED_FIELD,
                        child(path, name), "declared required but absent"));
            }
        }

        node.properties().forEach(entry -> {
            Schema<?> property = properties.get(entry.getKey());
            String childPath = child(path, entry.getKey());
            if (property == null) {
                if (!allowsAdditional(schema)) {
                    report(mismatches, reported, ContractMismatch.at(MismatchKind.UNDOCUMENTED_FIELD,
                            childPath, "returned but not declared in the response schema"));
                }
                return;
            }
            walk(property, entry.getValue(), childPath, mismatches, reported, depth + 1);
        });
    }

    /** A schema that explicitly opts into extra properties is not lying when it returns them. */
    private boolean allowsAdditional(Schema<?> schema) {
        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Boolean allowed) {
            return allowed;
        }
        return additional != null;
    }

    private boolean compatible(String type, JsonNode node) {
        return switch (type) {
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "array" -> node.isArray();
            case "object" -> node.isObject();
            default -> true;
        };
    }

    private String child(String parent, String field) {
        if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return parent + "." + field;
        }
        return parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private void report(List<ContractMismatch> mismatches, Set<String> reported, ContractMismatch mismatch) {
        if (reported.add(mismatch.kind() + " " + mismatch.location())) {
            mismatches.add(mismatch);
        }
    }
}
