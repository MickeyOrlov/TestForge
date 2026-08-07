package io.testforge.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Keeps credentials out of test logs and CI artifacts.
 *
 * <p>A test log is an artifact: it gets uploaded, attached to tickets and kept
 * for weeks. Anything this module prints goes through here first.
 */
public class Redactor {

    private static final String MASK = "***";

    private final ObjectMapper mapper;
    private final Set<String> headerNames;
    private final Set<String> jsonFields;

    public Redactor(ObjectMapper mapper, List<String> headerNames, List<String> jsonFields) {
        this.mapper = mapper;
        this.headerNames = Set.copyOf(headerNames);
        this.jsonFields = Set.copyOf(jsonFields);
    }

    public String header(String name, String value) {
        return headerNames.contains(name.toLowerCase(Locale.ROOT)) ? MASK : value;
    }

    /**
     * Masks configured fields in a JSON body. Bodies that are not JSON fall
     * back to a textual pass over {@code "field": "value"} and
     * {@code field=value} pairs — enough for form posts and query strings,
     * not a general-purpose sanitizer.
     */
    public String body(String body) {
        if (body == null || body.isBlank() || jsonFields.isEmpty()) {
            return body;
        }

        try {
            JsonNode root = mapper.readTree(body);
            if (root.isContainerNode()) {
                redact(root);
                return mapper.writeValueAsString(root);
            }
        } catch (Exception e) {
            // not JSON — fall through to the textual pass
        }
        return redactText(body);
    }

    private void redact(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (jsonFields.contains(name.toLowerCase(Locale.ROOT))) {
                    object.put(name, MASK);
                } else {
                    redact(object.get(name));
                }
            }
        } else if (node.isArray()) {
            node.forEach(this::redact);
        }
    }

    private String redactText(String body) {
        String result = body;
        for (String field : jsonFields) {
            String quoted = Pattern.quote(field);
            result = result.replaceAll("(?i)(\"" + quoted + "\"\\s*:\\s*\")[^\"]*(\")", "$1" + MASK + "$2");
            result = result.replaceAll("(?i)(\\b" + quoted + "=)[^&\\s]*", "$1" + MASK);
        }
        return result;
    }
}
