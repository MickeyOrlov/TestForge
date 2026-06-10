package io.testforge.data;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class TemplateRenderer {

    private static final String START = "%{";
    private static final String END = "}%";

    private final DataProperties properties;

    public TemplateRenderer(DataProperties properties) {
        this.properties = properties;
    }

    public String render(String template, Map<String, ?> variables) {
        return render(template, name -> Optional.ofNullable(variables.get(name)));
    }

    public String render(String template, Function<String, Optional<?>> variableResolver) {
        if (template == null) {
            return null;
        }
        if (variableResolver == null) {
            throw new IllegalArgumentException("variableResolver must not be null");
        }
        return render(template, variableResolver, new LinkedHashSet<>(), 0);
    }

    private String render(
            String template,
            Function<String, Optional<?>> variableResolver,
            Set<String> resolving,
            int depth) {
        if (depth > properties.maxTemplateDepth()) {
            throw new IllegalStateException("Template expansion exceeded max depth: " + properties.maxTemplateDepth());
        }

        StringBuilder result = new StringBuilder();
        int cursor = 0;

        while (cursor < template.length()) {
            int start = template.indexOf(START, cursor);
            if (start < 0) {
                result.append(template.substring(cursor));
                break;
            }

            result.append(template, cursor, start);
            int end = template.indexOf(END, start + START.length());
            if (end < 0) {
                throw new IllegalArgumentException("Unclosed template variable in: " + template);
            }

            String name = template.substring(start + START.length(), end);
            if (name.isBlank()) {
                throw new IllegalArgumentException("Template variable name must not be blank");
            }
            if (!resolving.add(name)) {
                throw new IllegalStateException("Cyclic template variable reference: " + resolving + " -> " + name);
            }

            Object value = variableResolver.apply(name)
                    .orElseThrow(() -> new IllegalArgumentException("No value for template variable: " + name));
            result.append(render(String.valueOf(value), variableResolver, resolving, depth + 1));
            resolving.remove(name);
            cursor = end + END.length();
        }

        return result.toString();
    }
}
