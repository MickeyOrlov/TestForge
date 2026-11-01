package io.testforge.gherkin;

import io.testforge.core.context.ContextKey;
import io.testforge.core.context.ScenarioContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values one step remembers and a later step of the same scenario uses:
 * {@code I remember the response field "$.id" as "orderId"}, then
 * {@code "/orders/${orderId}"}. Kept in {@link ScenarioContext}, so they end
 * with the scenario and step classes of different modules share them.
 */
public class ScenarioVariables {

    private static final Pattern REFERENCE = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private static final ContextKey<Values> VALUES = ContextKey.of("GHERKIN_VARIABLES", Values.class);

    private static final class Values {
        final Map<String, String> byName = new LinkedHashMap<>();
    }

    public void remember(String name, String value) {
        values().byName.put(name, value);
    }

    /** The value, or a failure that lists what this scenario did remember. */
    public String get(String name) {
        Values values = values();
        if (!values.byName.containsKey(name)) {
            throw new IllegalArgumentException("No scenario variable '" + name + "'; this scenario remembered "
                    + values.byName.keySet());
        }
        return values.byName.get(name);
    }

    /** {@code text} with every {@code ${name}} replaced; an unknown name fails. */
    public String resolve(String text) {
        if (text == null || !text.contains("${")) {
            return text;
        }
        Matcher matcher = REFERENCE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(get(matcher.group(1))));
        }
        return matcher.appendTail(out).toString();
    }

    private static Values values() {
        return ScenarioContext.find(VALUES).orElseGet(() -> {
            Values created = new Values();
            ScenarioContext.put(VALUES, created);
            return created;
        });
    }
}
