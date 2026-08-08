package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * How a parameter's value reaches the wire, according to its own
 * {@code style} and {@code explode}.
 *
 * <p>This exists so that a serialization defect is never mistaken for an API
 * defect. A {@code tags} parameter declared as an array and sent as the literal
 * string {@code testforge} is malformed before it arrives; whatever the service
 * answers says nothing about its validation, and a {@code 400} recorded there
 * would read as the service correctly rejecting a value it never received in
 * the form the document describes.
 *
 * <p>So each style is either produced correctly or refused by name. The refusals
 * are the interesting half: {@code form} with {@code explode: true} — which is
 * the default for query parameters — needs the same key repeated, and the
 * request model in use here carries one value per name. Rather than quietly
 * comma-joining it into a shape the document did not describe, the parameter is
 * reported as unsupported and no case is generated for it.
 */
final class ParameterSerialization {

    private ParameterSerialization() {
    }

    static String style(Parameter parameter) {
        if (parameter.getStyle() != null) {
            return parameter.getStyle().toString();
        }
        // OpenAPI's own defaults, which differ by location
        return switch (String.valueOf(parameter.getIn())) {
            case "query", "cookie" -> "form";
            default -> "simple";
        };
    }

    static boolean explode(Parameter parameter) {
        if (parameter.getExplode() != null) {
            return parameter.getExplode();
        }
        return "form".equals(style(parameter));
    }

    /**
     * Empty when the parameter can be sent faithfully; otherwise the reason it
     * cannot, phrased for a report a human reads.
     */
    static Optional<String> unsupported(Parameter parameter) {
        Schema<?> schema = parameter.getSchema();
        String type = Schemas.type(schema);
        String style = style(parameter);

        if ("object".equals(type) || "deepObject".equals(style)) {
            return Optional.of("object-valued parameters (style '" + style
                    + "') are not serialized by this module, so no case for it could be shown to be well-formed");
        }
        if ("label".equals(style) || "matrix".equals(style)) {
            return Optional.of("parameter style '" + style
                    + "' rewrites the path segment itself, which this module does not construct");
        }
        if (!"array".equals(type)) {
            return Optional.empty();
        }
        if (explode(parameter)) {
            return Optional.of("style '" + style + "' with explode=true repeats the parameter name per element, "
                    + "and the request model carries one value per name; comma-joining it would send a shape the "
                    + "document does not describe");
        }
        if (Schemas.type(schema.getItems()) == null
                || "object".equals(Schemas.type(schema.getItems()))
                || "array".equals(Schemas.type(schema.getItems()))) {
            return Optional.of("array items are not a scalar type, so no element value can be serialized");
        }
        return Optional.empty();
    }

    /** The delimiter the declared style puts between array elements. */
    static String delimiter(Parameter parameter) {
        return switch (style(parameter)) {
            case "spaceDelimited" -> " ";
            case "pipeDelimited" -> "|";
            default -> ",";
        };
    }

    static String join(Parameter parameter, List<String> elements) {
        return String.join(delimiter(parameter), elements);
    }

    /**
     * {@code size} element values for one array parameter, distinct when the
     * schema declares {@code uniqueItems}.
     *
     * <p>Empty when that many valid elements cannot be produced — three
     * different values out of a two-member enum, say. Returning nothing there is
     * the point: a request with a repeated element would violate
     * {@code uniqueItems} as well as whatever the case meant to test.
     */
    static Optional<List<String>> elements(Schema<?> arraySchema, int size, ConstraintAwareValueFactory values) {
        if (size == 0) {
            return Optional.of(List.of());
        }

        Schema<?> items = arraySchema.getItems();
        Optional<String> first = values.generate(items);
        if (first.isEmpty()) {
            return Optional.empty();
        }
        if (!SchemaFacts.uniqueItems(arraySchema)) {
            return Optional.of(java.util.Collections.nCopies(size, first.get()));
        }

        Set<String> distinct = new LinkedHashSet<>();
        distinct.add(first.get());
        for (int index = 1; index < size; index++) {
            String next = distinct(items, first.get(), index, distinct);
            if (next == null) {
                return Optional.empty();
            }
            distinct.add(next);
        }
        return Optional.of(List.copyOf(distinct));
    }

    private static String distinct(Schema<?> items, String first, int index, Set<String> seen) {
        Optional<List<String>> enumValues = SchemaFacts.enumValues(items);
        if (enumValues.isPresent()) {
            return enumValues.get().stream().filter(value -> !seen.contains(value)).findFirst().orElse(null);
        }

        String type = Schemas.type(items);
        if ("boolean".equals(type)) {
            return seen.contains("false") ? null : "false";
        }
        if ("integer".equals(type) || "number".equals(type)) {
            BigDecimal step = SchemaFacts.multipleOf(items).orElse(BigDecimal.ONE);
            BigDecimal base = new BigDecimal(first);
            for (int attempt = 1; attempt <= index + 4; attempt++) {
                BigDecimal candidate = base.add(step.multiply(BigDecimal.valueOf(attempt)));
                String text = candidate.stripTrailingZeros().toPlainString();
                if (SchemaFacts.satisfiesNumeric(items, candidate) && !seen.contains(text)) {
                    return text;
                }
            }
            return null;
        }

        List<String> candidates = new ArrayList<>();
        if (!first.isEmpty()) {
            // vary in place: appending would break maxLength and most patterns
            char replacement = (char) ('a' + ((first.charAt(first.length() - 1) - 'a' + index) % 26));
            candidates.add(first.substring(0, first.length() - 1) + replacement);
        }
        candidates.add(first + index);
        candidates.add("a".repeat(index + 1));

        return candidates.stream()
                .filter(candidate -> SchemaFacts.satisfiesString(items, candidate) && !seen.contains(candidate))
                .findFirst()
                .orElse(null);
    }
}
