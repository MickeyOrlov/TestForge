package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.SchemaValueFactory;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Parameter values that satisfy the constraints, not merely the type.
 *
 * <p>The explorer's factory answers "what is a plausible string" — which is the
 * right question for exploring, where the value is incidental. Fuzzing asks a
 * different one: the baseline is the control, and a control that violates
 * {@code maxLength} makes every case beneath it meaningless.
 *
 * <p>That defect was invisible until v1.2 sent a control request: the generated
 * {@code "testforge"} is nine characters, and against a parameter declared
 * {@code maxLength: 8} the baseline itself was invalid. Extending the explorer's
 * factory rather than replacing it keeps the fix inside this module.
 */
public class ConstraintAwareValueFactory extends SchemaValueFactory {

    @Override
    public Optional<String> generate(Schema<?> schema) {
        Optional<List<String>> enumValues = SchemaFacts.enumValues(schema);
        if (enumValues.isPresent()) {
            return Optional.of(enumValues.get().getFirst());
        }

        String type = Schemas.type(schema);
        if ("integer".equals(type) || "number".equals(type)) {
            return number(schema);
        }
        if (type == null || "string".equals(type)) {
            return string(schema);
        }
        return super.generate(schema);
    }

    private Optional<String> number(Schema<?> schema) {
        BigDecimal candidate = SchemaFacts.inclusiveMinimum(schema)
                .or(() -> SchemaFacts.exclusiveMinimum(schema).map(bound -> bound.add(BigDecimal.ONE)))
                .or(() -> SchemaFacts.multipleOf(schema))
                .orElse(BigDecimal.ONE);

        Optional<BigDecimal> factor = SchemaFacts.multipleOf(schema);
        if (factor.isPresent() && factor.get().signum() != 0
                && candidate.remainder(factor.get()).compareTo(BigDecimal.ZERO) != 0) {
            candidate = candidate.divide(factor.get(), 0, RoundingMode.CEILING).multiply(factor.get());
        }
        if (!SchemaFacts.satisfiesNumeric(schema, candidate)) {
            // no number satisfies everything declared; the self-check will
            // report it rather than this quietly sending a wrong one
            return Optional.empty();
        }
        return Optional.of(SchemaFacts.integer(schema)
                ? candidate.stripTrailingZeros().toPlainString()
                : candidate.toPlainString());
    }

    private Optional<String> string(Schema<?> schema) {
        String base = super.generate(schema).orElse("testforge");

        int minLength = schema == null || schema.getMinLength() == null ? 0 : schema.getMinLength();
        Integer maxLength = schema == null ? null : schema.getMaxLength();

        List<String> candidates = List.of(
                base,
                trim(base, maxLength),
                "a".repeat(Math.max(minLength, 1)),
                maxLength != null ? "a".repeat(Math.max(Math.min(maxLength, 8), 1)) : base);

        return candidates.stream()
                .filter(candidate -> SchemaFacts.satisfiesString(schema, candidate))
                .findFirst();
    }

    private String trim(String value, Integer maxLength) {
        return maxLength != null && value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
