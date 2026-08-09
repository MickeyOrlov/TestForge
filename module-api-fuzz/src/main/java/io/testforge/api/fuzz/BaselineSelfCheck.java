package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the control request really is valid before it is sent.
 *
 * <p>The whole differential model rests on one assumption: that the control
 * satisfies the document. If it does not, an accepted control proves the
 * service is lax rather than that the operation is reachable, and a rejected
 * one gets blamed on the service rather than on this module.
 *
 * <p>So the assumption is checked rather than trusted. It has already caught
 * one real defect — a generated parameter value longer than its declared
 * {@code maxLength} — and a configured override that violates its own schema
 * is reported the same way, since that is a mistake worth telling a user about
 * rather than fuzzing around.
 */
public class BaselineSelfCheck {

    /** Empty when the control is provably valid; otherwise the reason it is not. */
    public Optional<String> verify(ExplorableOperation operation, PreparedRequest request) {
        List<String> problems = new ArrayList<>();

        Map<String, String> values = new java.util.LinkedHashMap<>(request.pathParameters());
        values.putAll(request.queryParameters());

        for (Parameter parameter : operation.parameters()) {
            String value = values.get(parameter.getName());
            if (value == null) {
                continue;
            }
            violation(parameter, parameter.getSchema(), value).ifPresent(problem ->
                    problems.add("%s:%s %s".formatted(parameter.getIn(), parameter.getName(), problem)));
        }

        return problems.isEmpty()
                ? Optional.empty()
                : Optional.of("the control request would violate the document: " + String.join("; ", problems));
    }

    private Optional<String> violation(Parameter parameter, Schema<?> schema, String value) {
        if (schema == null) {
            return Optional.empty();
        }

        if ("array".equals(Schemas.type(schema))) {
            return arrayViolation(parameter, schema, value);
        }

        Optional<List<String>> enumValues = SchemaFacts.enumValues(schema);
        if (enumValues.isPresent() && !enumValues.get().contains(value)) {
            return Optional.of("is not one of the declared enum values");
        }

        String type = Schemas.type(schema);
        if ("integer".equals(type) || "number".equals(type)) {
            try {
                return SchemaFacts.satisfiesNumeric(schema, new BigDecimal(value))
                        ? Optional.empty()
                        : Optional.of("does not satisfy the declared numeric constraints");
            } catch (NumberFormatException e) {
                return Optional.of("is not a number");
            }
        }
        return SchemaFacts.satisfiesString(schema, value)
                ? Optional.empty()
                : Optional.of("does not satisfy the declared string constraints");
    }

    /**
     * An array parameter is checked after serialization, element by element.
     * Splitting the value back apart is not merely convenient: it is the only
     * way to notice that the joined form violates {@code uniqueItems} or that
     * the chosen delimiter appears inside an element.
     */
    private Optional<String> arrayViolation(Parameter parameter, Schema<?> schema, String value) {
        String delimiter = ParameterSerialization.delimiter(parameter);
        List<String> elements = value.isEmpty() ? List.of() : List.of(value.split(java.util.regex.Pattern.quote(delimiter), -1));

        if (schema.getMinItems() != null && elements.size() < schema.getMinItems()) {
            return Optional.of("serializes to fewer than the declared minItems");
        }
        if (schema.getMaxItems() != null && elements.size() > schema.getMaxItems()) {
            return Optional.of("serializes to more than the declared maxItems");
        }
        if (SchemaFacts.uniqueItems(schema) && Set.copyOf(elements).size() != elements.size()) {
            return Optional.of("repeats an element against the declared uniqueItems");
        }

        for (String element : elements) {
            Optional<String> problem = violation(parameter, schema.getItems(), element);
            if (problem.isPresent()) {
                return problem.map(detail -> "has an element that " + detail);
            }
        }
        return Optional.empty();
    }
}
