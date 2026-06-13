package io.testforge.contract.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.ContractMappers;
import java.util.Map;
import java.util.TreeMap;

public class PayloadShapeNormalizer {

    private final ObjectMapper objectMapper;

    public PayloadShapeNormalizer() {
        this(ContractMappers.strict());
    }

    public PayloadShapeNormalizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, String> normalize(String json) {
        try {
            Map<String, String> shape = new TreeMap<>();
            walk("$", objectMapper.readTree(json), shape);
            return Map.copyOf(shape);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    private void walk(String path, JsonNode node, Map<String, String> shape) {
        if (node.isObject()) {
            put(shape, path, "OBJECT");
            node.properties().forEach(entry -> walk(child(path, entry.getKey()), entry.getValue(), shape));
            return;
        }
        if (node.isArray()) {
            put(shape, path, "ARRAY");
            node.forEach(item -> walk(path + "[]", item, shape));
            return;
        }
        put(shape, path, typeOf(node));
    }

    private void put(Map<String, String> shape, String path, String type) {
        shape.merge(path, type, (left, right) -> left.equals(right) ? left : "MIXED");
    }

    private String child(String parent, String field) {
        if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return parent + "." + field;
        }
        return parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    private String typeOf(JsonNode node) {
        if (node.isTextual()) {
            return "STRING";
        }
        if (node.isIntegralNumber()) {
            return "INTEGER";
        }
        if (node.isFloatingPointNumber()) {
            return "NUMBER";
        }
        if (node.isBoolean()) {
            return "BOOLEAN";
        }
        if (node.isNull()) {
            return "NULL";
        }
        return node.getNodeType().name();
    }
}
