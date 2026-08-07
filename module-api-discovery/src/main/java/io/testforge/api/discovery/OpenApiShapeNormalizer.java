package io.testforge.api.discovery;

import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class OpenApiShapeNormalizer {

    public List<ApiSchemaShape> normalize(EndpointCatalog catalog, io.swagger.v3.oas.models.OpenAPI openApi) {
        List<ApiSchemaShape> shapes = new java.util.ArrayList<>();
        if (openApi.getPaths() == null) {
            return List.of();
        }

        for (ApiEndpoint endpoint : catalog.endpoints()) {
            io.swagger.v3.oas.models.Operation operation = operation(openApi, endpoint);
            if (operation == null) {
                continue;
            }
            requestShapes(endpoint, operation).forEach(shapes::add);
            responseShapes(endpoint, operation).forEach(shapes::add);
        }

        return List.copyOf(shapes);
    }

    private List<ApiSchemaShape> requestShapes(ApiEndpoint endpoint, io.swagger.v3.oas.models.Operation operation) {
        if (operation.getRequestBody() == null) {
            return List.of();
        }
        return contentShapes(endpoint, "request", null, operation.getRequestBody().getContent());
    }

    private List<ApiSchemaShape> responseShapes(ApiEndpoint endpoint, io.swagger.v3.oas.models.Operation operation) {
        if (operation.getResponses() == null) {
            return List.of();
        }
        List<ApiSchemaShape> shapes = new java.util.ArrayList<>();
        operation.getResponses().forEach((status, response) -> {
            Content content = response == null ? null : response.getContent();
            shapes.addAll(contentShapes(endpoint, "response", status, content));
        });
        return shapes;
    }

    private List<ApiSchemaShape> contentShapes(
            ApiEndpoint endpoint,
            String direction,
            String status,
            Content content) {
        if (content == null) {
            return List.of();
        }

        List<ApiSchemaShape> shapes = new java.util.ArrayList<>();
        content.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    MediaType mediaType = entry.getValue();
                    if (mediaType == null || mediaType.getSchema() == null) {
                        return;
                    }
                    String name = shapeName(endpoint.method(), endpoint.path(), direction, status, entry.getKey());
                    shapes.add(new ApiSchemaShape(
                            name,
                            endpoint.key(),
                            endpoint.method(),
                            endpoint.path(),
                            direction,
                            status,
                            entry.getKey(),
                            normalize(mediaType.getSchema())));
                });
        return shapes;
    }

    public Map<String, SchemaShapeEntry> normalize(Schema<?> schema) {
        Map<String, SchemaShapeEntry> shape = new TreeMap<>();
        walk("$", schema, true, shape);
        return Map.copyOf(shape);
    }

    private void walk(String path, Schema<?> schema, boolean required, Map<String, SchemaShapeEntry> shape) {
        if (schema == null) {
            put(shape, path, new SchemaShapeEntry("UNKNOWN", required, false));
            return;
        }
        if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
            put(shape, path, new SchemaShapeEntry(typeOf(schema), required, nullable(schema)));
            schema.getAllOf().forEach(part -> walk(path, part, required, shape));
            return;
        }
        if (schema.getOneOf() != null && !schema.getOneOf().isEmpty()) {
            put(shape, path, new SchemaShapeEntry("ONE_OF", required, nullable(schema)));
            schema.getOneOf().forEach(part -> walk(path, part, required, shape));
            return;
        }
        if (schema.getAnyOf() != null && !schema.getAnyOf().isEmpty()) {
            put(shape, path, new SchemaShapeEntry("ANY_OF", required, nullable(schema)));
            schema.getAnyOf().forEach(part -> walk(path, part, required, shape));
            return;
        }

        String type = typeOf(schema);
        put(shape, path, new SchemaShapeEntry(type, required, nullable(schema)));
        if ("ARRAY".equals(type)) {
            walk(path + "[]", arrayItems(schema), true, shape);
            return;
        }
        if ("OBJECT".equals(type)) {
            walkObject(path, schema, shape);
        }
    }

    private void walkObject(String path, Schema<?> schema, Map<String, SchemaShapeEntry> shape) {
        List<String> requiredFields = schema.getRequired() == null ? List.of() : schema.getRequired();
        Map<String, Schema> properties = schema.getProperties() == null ? Map.of() : schema.getProperties();
        properties.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> walk(
                        child(path, entry.getKey()),
                        entry.getValue(),
                        requiredFields.contains(entry.getKey()),
                        shape));

        Object additional = schema.getAdditionalProperties();
        if (additional instanceof Schema<?> additionalSchema) {
            walk(child(path, "*"), additionalSchema, false, shape);
        }
    }

    private Schema<?> arrayItems(Schema<?> schema) {
        if (schema instanceof ArraySchema arraySchema) {
            return arraySchema.getItems();
        }
        return schema.getItems();
    }

    private String typeOf(Schema<?> schema) {
        String type = schema.getType();
        if (type == null) {
            if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                return "OBJECT";
            }
            if (schema.getItems() != null) {
                return "ARRAY";
            }
            if (schema.getAllOf() != null && !schema.getAllOf().isEmpty()) {
                return "OBJECT";
            }
            return "UNKNOWN";
        }
        return switch (type) {
            case "object" -> "OBJECT";
            case "array" -> "ARRAY";
            case "integer" -> "INTEGER";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "string" -> "STRING";
            default -> type.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private boolean nullable(Schema<?> schema) {
        return Boolean.TRUE.equals(schema.getNullable());
    }

    private void put(Map<String, SchemaShapeEntry> shape, String path, SchemaShapeEntry entry) {
        shape.merge(path, entry, this::merge);
    }

    private SchemaShapeEntry merge(SchemaShapeEntry left, SchemaShapeEntry right) {
        String type = left.type().equals(right.type()) ? left.type() : "MIXED";
        return new SchemaShapeEntry(type, left.required() && right.required(), left.nullable() || right.nullable());
    }

    private io.swagger.v3.oas.models.Operation operation(io.swagger.v3.oas.models.OpenAPI openApi, ApiEndpoint endpoint) {
        io.swagger.v3.oas.models.PathItem pathItem = openApi.getPaths().get(endpoint.path());
        if (pathItem == null) {
            return null;
        }
        return pathItem.readOperationsMap().entrySet().stream()
                .filter(entry -> entry.getKey().name().equals(endpoint.method()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private String child(String parent, String field) {
        if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return parent + "." + field;
        }
        return parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private String shapeName(String method, String path, String direction, String status, String contentType) {
        StringBuilder name = new StringBuilder();
        name.append(method.toLowerCase(java.util.Locale.ROOT))
                .append("__")
                .append(safe(path));
        if ("response".equals(direction)) {
            name.append("__response__").append(safe(status));
        } else {
            name.append("__request");
        }
        name.append("__").append(safe(contentType)).append(".shape.json");
        return name.toString();
    }

    private String safe(String value) {
        String safe = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[{}]", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("(^_+|_+$)", "");
        return safe.isBlank() ? "default" : safe;
    }
}
