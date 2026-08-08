package io.testforge.api.explorer;

import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import java.util.Map;
import java.util.Optional;

/**
 * Decides what to send for one parameter, and records why.
 *
 * <p>The order is fixed and runs from most authoritative to least: a value a
 * human configured, then one the document offers as an example, then its
 * default, then the first of its enum, and only then something derived from the
 * type. Anything the document states about itself beats anything this module
 * invents.
 */
public class RequestValueResolver {

    private final ApiExplorerProperties.ParameterProperties configured;
    private final SchemaValueFactory values;

    public RequestValueResolver(ApiExplorerProperties.ParameterProperties configured, SchemaValueFactory values) {
        this.configured = configured;
        this.values = values;
    }

    public Optional<ParameterBinding> resolve(ExplorableOperation operation, Parameter parameter) {
        Schema<?> schema = parameter.getSchema();

        String override = configured.find(operation.operationId(), parameter.getName());
        if (override != null && !override.isBlank()) {
            return binding(parameter, ValueSource.CONFIGURED, override);
        }

        Optional<String> example = example(parameter, schema);
        if (example.isPresent()) {
            return binding(parameter, ValueSource.EXAMPLE, example.get());
        }

        String declaredDefault = text(schema == null ? null : schema.getDefault());
        if (declaredDefault != null) {
            return binding(parameter, ValueSource.DEFAULT, declaredDefault);
        }

        String firstEnum = firstEnum(schema);
        if (firstEnum != null) {
            return binding(parameter, ValueSource.ENUM, firstEnum);
        }

        return values.generate(schema)
                .flatMap(generated -> binding(parameter, ValueSource.GENERATED, generated));
    }

    private Optional<String> example(Parameter parameter, Schema<?> schema) {
        String direct = text(parameter.getExample());
        if (direct != null) {
            return Optional.of(direct);
        }

        Map<String, Example> examples = parameter.getExamples();
        if (examples != null) {
            for (Example example : examples.values()) {
                String value = example == null ? null : text(example.getValue());
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }

        return Optional.ofNullable(text(schema == null ? null : schema.getExample()));
    }

    private String firstEnum(Schema<?> schema) {
        if (schema == null || schema.getEnum() == null || schema.getEnum().isEmpty()) {
            return null;
        }
        return text(schema.getEnum().getFirst());
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    private Optional<ParameterBinding> binding(Parameter parameter, ValueSource source, String value) {
        return Optional.of(new ParameterBinding(parameter.getName(), parameter.getIn(), source, value));
    }
}
