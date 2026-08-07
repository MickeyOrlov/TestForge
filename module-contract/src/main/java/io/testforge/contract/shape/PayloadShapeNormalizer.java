package io.testforge.contract.shape;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.ContractMappers;
import java.util.Map;
import java.util.TreeMap;

public class PayloadShapeNormalizer {

    /** Path of the document root in shape maps. */
    public static final String ROOT = "$";

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
            walk(ROOT, objectMapper.readTree(json), shape);
            return Map.copyOf(shape);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Payload is not valid JSON: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Path of {@code field} inside {@code parent}. Public because anything that
     * compares against a shape map — a schema projection, a value profile —
     * has to produce byte-identical paths; a second implementation of this
     * would drift and report differences that are not there.
     */
    public static String childPath(String parent, String field) {
        if (field.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return parent + "." + field;
        }
        return parent + "['" + field.replace("\\", "\\\\").replace("'", "\\'") + "']";
    }

    /**
     * Path of an array element. Indices are deliberately collapsed: a shape is
     * about what the elements look like, not how many there happened to be.
     */
    public static String elementPath(String parent) {
        return parent + "[]";
    }

    private void walk(String path, JsonNode node, Map<String, String> shape) {
        if (node.isObject()) {
            put(shape, path, "OBJECT");
            node.properties().forEach(entry -> walk(childPath(path, entry.getKey()), entry.getValue(), shape));
            return;
        }
        if (node.isArray()) {
            put(shape, path, "ARRAY");
            node.forEach(item -> walk(elementPath(path), item, shape));
            return;
        }
        put(shape, path, typeOf(node));
    }

    private void put(Map<String, String> shape, String path, String type) {
        shape.merge(path, type, (left, right) -> left.equals(right) ? left : "MIXED");
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
