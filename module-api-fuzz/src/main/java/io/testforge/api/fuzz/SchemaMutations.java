package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Every mutation this module knows how to make to one value, together with the
 * expectation the document earns for it.
 *
 * <p>One implementation serves both parameters and request-body fields. A rule
 * that held for {@code ?age=} but not for {@code $.profile.age} would be a rule
 * nobody could explain.
 *
 * <p>The discipline is in the split. A constraint the document declares
 * produces a {@code REJECT} case; the valid edge of that constraint produces an
 * {@code ACCEPT} case; anything else — long strings where no length is
 * declared, negatives where no minimum is — produces {@code UNSPECIFIED}. Only
 * the first kind can ever justify an {@code OVER_PERMISSIVE} verdict.
 */
final class SchemaMutations {

    private static final int LONG_STRING = 4096;
    private static final String ENCODING_PROBE = "tf'\"<>&\n|";
    private static final String UNICODE = "tf-Ω-日本語-🙂";

    /**
     * Tried in order until one provably fails the declared pattern. Deliberately
     * varied — letters, spaces, punctuation, digits — so that a common pattern
     * yields a case rather than silently producing none.
     */
    private static final List<String> PATTERN_CANDIDATES = List.of(
            "testforge-pattern-violation",
            "TESTFORGE PATTERN VIOLATION",
            "!@#$%^&*()",
            "0000000000",
            " ");

    private SchemaMutations() {
    }

    /** One change to make, and what the document says about it. */
    record Mutation(FuzzCaseKind kind, FuzzExpectation expectation, Object value) {

        static Mutation proven(FuzzCaseKind kind, Object value) {
            return new Mutation(kind, FuzzExpectation.REJECT, value);
        }

        static Mutation valid(FuzzCaseKind kind, Object value) {
            return new Mutation(kind, FuzzExpectation.ACCEPT, value);
        }

        static Mutation probe(FuzzCaseKind kind, Object value) {
            return new Mutation(kind, FuzzExpectation.UNSPECIFIED, value);
        }
    }

    /**
     * @param nullable whether the surrounding context permits an explicit null;
     *                 only request bodies can express one at all
     */
    static List<Mutation> forSchema(Schema<?> schema, boolean bodyContext) {
        List<Mutation> mutations = new ArrayList<>();

        Optional<List<String>> enumValues = SchemaFacts.enumValues(schema);
        if (enumValues.isPresent()) {
            // an enum defines the entire value space; length or range cases on
            // top of it would be noise, and their expectations meaningless
            mutations.add(Mutation.proven(FuzzCaseKind.ENUM_OUTSIDER, "testforge-not-in-enum"));
            addNullCase(mutations, schema, bodyContext);
            return mutations;
        }

        String type = Schemas.type(schema);
        if (type == null || "string".equals(type)) {
            addStringMutations(mutations, schema);
        } else if ("integer".equals(type) || "number".equals(type)) {
            addNumericMutations(mutations, schema);
        } else if ("boolean".equals(type)) {
            mutations.add(Mutation.proven(FuzzCaseKind.WRONG_TYPE, "testforge"));
        } else if ("array".equals(type) && bodyContext) {
            addArrayMutations(mutations, schema);
        }

        addNullCase(mutations, schema, bodyContext);
        return List.copyOf(mutations);
    }

    private static void addNullCase(List<Mutation> mutations, Schema<?> schema, boolean bodyContext) {
        // a URL cannot carry a JSON null, so this only means something in a body
        if (bodyContext && !Schemas.nullable(schema)) {
            mutations.add(new Mutation(FuzzCaseKind.NULL_FOR_NON_NULLABLE, FuzzExpectation.REJECT, null));
        }
    }

    private static void addStringMutations(List<Mutation> mutations, Schema<?> schema) {
        Integer minLength = schema == null ? null : schema.getMinLength();
        Integer maxLength = schema == null ? null : schema.getMaxLength();
        Optional<Pattern> pattern = SchemaFacts.pattern(schema);

        // an empty string only breaks a promise when a minimum length exists
        mutations.add(minLength != null && minLength > 0
                ? Mutation.proven(FuzzCaseKind.EMPTY_STRING, "")
                : Mutation.probe(FuzzCaseKind.EMPTY_STRING, ""));

        mutations.add(Mutation.probe(FuzzCaseKind.ENCODING_PROBE, ENCODING_PROBE));
        mutations.add(Mutation.probe(FuzzCaseKind.UNICODE, UNICODE));

        if (maxLength != null && maxLength > 0) {
            addStringBoundary(mutations, schema, FuzzCaseKind.AT_UPPER_BOUND, "a".repeat(maxLength));
            mutations.add(Mutation.proven(FuzzCaseKind.TOO_LONG, "a".repeat(maxLength + 1)));
        } else {
            // nothing declares a maximum, so a long string is only a probe —
            // reporting OVER_PERMISSIVE here was a false finding in v1
            mutations.add(Mutation.probe(FuzzCaseKind.TOO_LONG, "a".repeat(LONG_STRING)));
        }

        if (minLength != null && minLength > 0) {
            addStringBoundary(mutations, schema, FuzzCaseKind.AT_LOWER_BOUND, "a".repeat(minLength));
            if (minLength > 1) {
                mutations.add(Mutation.proven(FuzzCaseKind.TOO_SHORT, "a".repeat(minLength - 1)));
            }
        }

        // only claim a violation when the value provably fails the pattern.
        // v1 sent one fixed string and asserted REJECT — against a pattern like
        // ^[a-z0-9-]+$ that string matches, so the case accused the service of
        // accepting something the document allowed
        pattern.ifPresent(compiled -> PATTERN_CANDIDATES.stream()
                .filter(candidate -> !compiled.matcher(candidate).find())
                .findFirst()
                .ifPresent(candidate ->
                        mutations.add(Mutation.proven(FuzzCaseKind.PATTERN_VIOLATION, candidate))));

        String format = Schemas.format(schema);
        if (SchemaFacts.knownFormat(format)) {
            mutations.add(Mutation.proven(FuzzCaseKind.FORMAT_VIOLATION, formatViolation(format)));
        } else if (format != null) {
            // a custom format is a word in the document, not a rule this module
            // can enforce
            mutations.add(Mutation.probe(FuzzCaseKind.FORMAT_VIOLATION, "testforge-format-probe"));
        }
    }

    /** A length boundary is only "valid" if it satisfies the other string constraints too. */
    private static void addStringBoundary(List<Mutation> mutations, Schema<?> schema,
                                          FuzzCaseKind kind, String value) {
        if (SchemaFacts.satisfiesString(schema, value)) {
            mutations.add(Mutation.valid(kind, value));
        }
    }

    private static void addNumericMutations(List<Mutation> mutations, Schema<?> schema) {
        boolean integer = SchemaFacts.integer(schema);
        mutations.add(Mutation.proven(FuzzCaseKind.WRONG_TYPE, "testforge"));
        if (integer) {
            mutations.add(Mutation.proven(FuzzCaseKind.FRACTIONAL_FOR_INTEGER, new BigDecimal("1.5")));
        }

        SchemaFacts.inclusiveMinimum(schema).ifPresent(min -> {
            addNumericBoundary(mutations, schema, FuzzCaseKind.AT_LOWER_BOUND, min);
            mutations.add(Mutation.proven(FuzzCaseKind.BELOW_MINIMUM, min.subtract(BigDecimal.ONE)));
        });
        SchemaFacts.inclusiveMaximum(schema).ifPresent(max -> {
            addNumericBoundary(mutations, schema, FuzzCaseKind.AT_UPPER_BOUND, max);
            mutations.add(Mutation.proven(FuzzCaseKind.ABOVE_MAXIMUM, max.add(BigDecimal.ONE)));
        });

        // exclusive: the bound itself is forbidden, and the nearest value past
        // it is the valid edge
        SchemaFacts.exclusiveMinimum(schema).ifPresent(bound -> {
            mutations.add(Mutation.proven(FuzzCaseKind.AT_EXCLUSIVE_BOUND, bound));
            addNumericBoundary(mutations, schema, FuzzCaseKind.AT_LOWER_BOUND, bound.add(BigDecimal.ONE));
        });
        SchemaFacts.exclusiveMaximum(schema).ifPresent(bound -> {
            mutations.add(Mutation.proven(FuzzCaseKind.AT_EXCLUSIVE_BOUND, bound));
            addNumericBoundary(mutations, schema, FuzzCaseKind.AT_UPPER_BOUND, bound.subtract(BigDecimal.ONE));
        });

        SchemaFacts.multipleOf(schema).ifPresent(factor -> {
            BigDecimal candidate = factor.multiply(BigDecimal.valueOf(2)).add(BigDecimal.ONE);
            if (candidate.remainder(factor).compareTo(BigDecimal.ZERO) != 0) {
                mutations.add(Mutation.proven(FuzzCaseKind.NOT_MULTIPLE_OF, candidate));
            }
        });

        if (SchemaFacts.inclusiveMinimum(schema).isEmpty() && SchemaFacts.exclusiveMinimum(schema).isEmpty()) {
            mutations.add(Mutation.probe(FuzzCaseKind.NEGATIVE, BigDecimal.valueOf(-1)));
        }
        if (SchemaFacts.inclusiveMaximum(schema).isEmpty() && SchemaFacts.exclusiveMaximum(schema).isEmpty()) {
            // no declared ceiling, so a huge value is a robustness probe rather
            // than a contract violation — another v1 false finding
            mutations.add(Mutation.probe(FuzzCaseKind.HUGE_NUMBER, BigDecimal.valueOf(Long.MAX_VALUE)));
        }
    }

    private static void addNumericBoundary(List<Mutation> mutations, Schema<?> schema,
                                           FuzzCaseKind kind, BigDecimal value) {
        if (SchemaFacts.satisfiesNumeric(schema, value)) {
            mutations.add(Mutation.valid(kind, value));
        }
    }

    private static void addArrayMutations(List<Mutation> mutations, Schema<?> schema) {
        Integer minItems = schema.getMinItems();
        Integer maxItems = schema.getMaxItems();

        mutations.add(minItems != null && minItems > 0
                ? Mutation.proven(FuzzCaseKind.EMPTY_ARRAY, 0)
                : Mutation.probe(FuzzCaseKind.EMPTY_ARRAY, 0));

        if (minItems != null && minItems > 0) {
            mutations.add(Mutation.valid(FuzzCaseKind.AT_LOWER_BOUND, minItems));
            if (minItems > 1) {
                mutations.add(Mutation.proven(FuzzCaseKind.TOO_FEW_ITEMS, minItems - 1));
            }
        }
        if (maxItems != null && maxItems > 0) {
            mutations.add(Mutation.valid(FuzzCaseKind.AT_UPPER_BOUND, maxItems));
            mutations.add(Mutation.proven(FuzzCaseKind.TOO_MANY_ITEMS, maxItems + 1));
        }

        if (Schemas.type(schema.getItems()) != null) {
            mutations.add(Mutation.proven(FuzzCaseKind.INVALID_ITEM_TYPE, null));
        }
    }

    private static String formatViolation(String format) {
        return switch (format) {
            case "uuid" -> "not-a-uuid";
            case "date" -> "2024-13-45";
            case "date-time" -> "2024-13-45T99:99:99Z";
            case "email" -> "not-an-email";
            case "uri", "url" -> "not a uri";
            case "ipv4", "ipv6" -> "999.999.999.999";
            case "byte" -> "not-base64!!";
            default -> "testforge-format-violation";
        };
    }
}
