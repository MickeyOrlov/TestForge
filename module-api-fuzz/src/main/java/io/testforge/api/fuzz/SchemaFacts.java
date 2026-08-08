package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * What a schema actually promises, asked one question at a time.
 *
 * <p>Every {@code REJECT} this module ever issues traces back to a method here
 * returning a present value. If the document declares no {@code maximum}, there
 * is no maximum to exceed, and no answer the service gives can contradict a
 * promise it never made.
 *
 * <p>OpenAPI spells exclusive bounds two ways — 3.0 pairs a boolean with
 * {@code minimum}, 3.1 carries the value itself — and both appear in real
 * documents, so both are read here rather than at every call site.
 */
final class SchemaFacts {

    private SchemaFacts() {
    }

    /** The largest value the schema forbids going at or below, when exclusive. */
    static Optional<BigDecimal> exclusiveMinimum(Schema<?> schema) {
        if (schema == null) {
            return Optional.empty();
        }
        if (schema.getExclusiveMinimumValue() != null) {
            return Optional.of(schema.getExclusiveMinimumValue());
        }
        return Boolean.TRUE.equals(schema.getExclusiveMinimum())
                ? Optional.ofNullable(schema.getMinimum())
                : Optional.empty();
    }

    static Optional<BigDecimal> exclusiveMaximum(Schema<?> schema) {
        if (schema == null) {
            return Optional.empty();
        }
        if (schema.getExclusiveMaximumValue() != null) {
            return Optional.of(schema.getExclusiveMaximumValue());
        }
        return Boolean.TRUE.equals(schema.getExclusiveMaximum())
                ? Optional.ofNullable(schema.getMaximum())
                : Optional.empty();
    }

    /** A minimum the schema allows reaching. Absent when the bound is exclusive. */
    static Optional<BigDecimal> inclusiveMinimum(Schema<?> schema) {
        if (schema == null || schema.getMinimum() == null || exclusiveMinimum(schema).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(schema.getMinimum());
    }

    static Optional<BigDecimal> inclusiveMaximum(Schema<?> schema) {
        if (schema == null || schema.getMaximum() == null || exclusiveMaximum(schema).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(schema.getMaximum());
    }

    static Optional<BigDecimal> multipleOf(Schema<?> schema) {
        return schema == null ? Optional.empty() : Optional.ofNullable(schema.getMultipleOf());
    }

    static boolean integer(Schema<?> schema) {
        return "integer".equals(Schemas.type(schema));
    }

    static Optional<Pattern> pattern(Schema<?> schema) {
        if (schema == null || schema.getPattern() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Pattern.compile(schema.getPattern()));
        } catch (PatternSyntaxException e) {
            // a pattern this JVM cannot compile proves nothing either way
            return Optional.empty();
        }
    }

    /**
     * Whether a number satisfies every numeric constraint the schema declares.
     * Used before claiming a boundary value is valid: {@code minimum: 1} with
     * {@code multipleOf: 5} makes 1 an <em>invalid</em> lower bound, and
     * asserting {@code ACCEPT} on it would be this module's own bug.
     */
    static boolean satisfiesNumeric(Schema<?> schema, BigDecimal value) {
        if (inclusiveMinimum(schema).filter(min -> value.compareTo(min) < 0).isPresent()) {
            return false;
        }
        if (inclusiveMaximum(schema).filter(max -> value.compareTo(max) > 0).isPresent()) {
            return false;
        }
        if (exclusiveMinimum(schema).filter(min -> value.compareTo(min) <= 0).isPresent()) {
            return false;
        }
        if (exclusiveMaximum(schema).filter(max -> value.compareTo(max) >= 0).isPresent()) {
            return false;
        }
        return multipleOf(schema)
                .map(factor -> factor.signum() != 0
                        && value.remainder(factor).compareTo(BigDecimal.ZERO) == 0)
                .orElse(true);
    }

    /** Whether a string satisfies every string constraint the schema declares. */
    static boolean satisfiesString(Schema<?> schema, String value) {
        if (schema == null) {
            return true;
        }
        if (schema.getMinLength() != null && value.length() < schema.getMinLength()) {
            return false;
        }
        if (schema.getMaxLength() != null && value.length() > schema.getMaxLength()) {
            return false;
        }
        if (enumValues(schema).isPresent() && !enumValues(schema).get().contains(value)) {
            return false;
        }
        return pattern(schema).map(pattern -> pattern.matcher(value).find()).orElse(true);
    }

    static boolean uniqueItems(Schema<?> schema) {
        return schema != null && Boolean.TRUE.equals(schema.getUniqueItems());
    }

    /**
     * Whether the schema forbids properties it did not declare.
     *
     * <p>Only the literal {@code additionalProperties: false} proves that. A
     * schema-valued {@code additionalProperties} constrains extra properties
     * rather than banning them, and its absence — by far the common case —
     * permits them outright, so neither can justify a {@code REJECT}.
     */
    static boolean additionalPropertiesForbidden(Schema<?> schema) {
        return schema != null && Boolean.FALSE.equals(schema.getAdditionalProperties());
    }

    /**
     * A property the document says belongs to responses only. OpenAPI's word is
     * "SHOULD NOT" be sent in a request, which is guidance rather than a rule —
     * so sending one is a probe, and leaving one out of the control is simply
     * doing what the document asks.
     */
    static boolean readOnly(Schema<?> schema) {
        return schema != null && Boolean.TRUE.equals(schema.getReadOnly());
    }

    static boolean writeOnly(Schema<?> schema) {
        return schema != null && Boolean.TRUE.equals(schema.getWriteOnly());
    }

    static Optional<List<String>> enumValues(Schema<?> schema) {
        if (schema == null || schema.getEnum() == null || schema.getEnum().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(schema.getEnum().stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .toList());
    }

    /** Formats whose violation this module can actually construct and defend. */
    static boolean knownFormat(String format) {
        if (format == null) {
            return false;
        }
        return switch (format) {
            case "uuid", "date", "date-time", "email", "uri", "url", "ipv4", "ipv6", "byte" -> true;
            default -> false;
        };
    }
}
