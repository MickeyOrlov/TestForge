package io.testforge.gherkin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.json.JsonPath;
import java.util.ArrayList;
import java.util.List;

/**
 * A field of a JSON body, for steps that remember or compare one. A failure
 * names where the body came from and the top-level field names — never the
 * body itself.
 */
public final class JsonFields {

    private JsonFields() {
    }

    /** @param source what the body is, for the failure: {@code GET /orders/1}, {@code orders.events/0@42} */
    public static String read(String json, String path, String source, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(json == null ? "" : json);
        } catch (JsonProcessingException e) {
            root = null;
        }
        if (root == null || root.isMissingNode()) {
            throw new AssertionError(source + ": the body is not JSON, so it has no field " + path);
        }
        JsonNode value = JsonPath.read(root, path);
        if (value.isMissingNode()) {
            throw new AssertionError(source + ": the body has no field " + path
                    + (root.isObject() ? "; top-level fields: " + names(root) : ""));
        }
        return value.isValueNode() ? value.asText() : value.toString();
    }

    private static List<String> names(JsonNode root) {
        List<String> names = new ArrayList<>();
        root.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
