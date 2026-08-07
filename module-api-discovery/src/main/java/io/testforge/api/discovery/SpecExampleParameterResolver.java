package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.Optional;

/**
 * Takes parameter values out of the document itself: {@code example},
 * {@code examples.*.value}, {@code schema.example}, {@code schema.default} or
 * the first {@code schema.enum} entry.
 *
 * <p>Off unless {@code forge.api-discovery.parameters.use-spec-examples=true}.
 * Examples in real documents are usually placeholders that turn a discovery run
 * into a wall of 404s — and occasionally they are a production identifier
 * somebody pasted in years ago. Neither is a good default.
 */
public class SpecExampleParameterResolver implements PathParameterResolver {

    public static final String SOURCE = "SPEC_EXAMPLE";

    @Override
    public String sourceName() {
        return SOURCE;
    }

    @Override
    public Optional<String> resolve(EndpointDescriptor endpoint, ParameterDescriptor parameter) {
        JsonNode node = parameter.node();

        Optional<String> direct = text(node.path("example"));
        if (direct.isPresent()) {
            return direct;
        }

        JsonNode examples = node.path("examples");
        if (examples.isObject()) {
            for (JsonNode example : examples) {
                Optional<String> value = text(example.path("value"));
                if (value.isPresent()) {
                    return value;
                }
            }
        }

        JsonNode schema = node.path("schema");
        return text(schema.path("example"))
                .or(() -> text(schema.path("default")))
                .or(() -> text(schema.path("enum").path(0)));
    }

    private Optional<String> text(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.isContainerNode()) {
            return Optional.empty();
        }
        String value = node.asText();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }
}
