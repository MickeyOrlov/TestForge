package io.testforge.api.fuzz;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.Schemas;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives cases from what the document actually says about each parameter.
 *
 * <p>Nothing here is random. Every value is a function of the schema, so two
 * runs against the same document generate the same cases with the same ids —
 * which is what makes a finding reproducible rather than a story about
 * something that happened once.
 *
 * <p>A parameter the document constrains produces sharp cases: one past
 * {@code maxLength}, one below {@code minimum}, one outside the {@code enum}.
 * A parameter it barely describes produces blunt ones. That asymmetry is
 * honest — the module can only test against what was written down, and an
 * operation whose parameters are declared as bare strings is telling you
 * something too.
 */
public class FuzzCaseGenerator {

    private static final int LONG_STRING = 4096;
    private static final String ENCODING_PROBE = "tf'\"<>&\n|";
    private static final String UNICODE = "tf-Ω-日本語-🙂";

    public List<FuzzCase> generate(ExplorableOperation operation) {
        List<FuzzCase> cases = new ArrayList<>();
        for (Parameter parameter : operation.parameters()) {
            String in = parameter.getIn();
            if (!"path".equals(in) && !"query".equals(in)) {
                // header and cookie parameters belong to the environment; the
                // explorer does not set them and neither does this
                continue;
            }
            cases.addAll(forParameter(operation, parameter));
        }
        return List.copyOf(cases);
    }

    private List<FuzzCase> forParameter(ExplorableOperation operation, Parameter parameter) {
        List<FuzzCase> cases = new ArrayList<>();
        Schema<?> schema = parameter.getSchema();
        String type = Schemas.type(schema);

        if (Boolean.TRUE.equals(parameter.getRequired()) && "query".equals(parameter.getIn())) {
            // omitting a required path parameter changes which endpoint is
            // addressed, so only query parameters can be dropped meaningfully
            cases.add(FuzzCase.omitting(operation.specId(), operation.operationId(), operation.key(),
                    parameter.getName(), parameter.getIn()));
        }

        if (schema != null && schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            add(cases, operation, parameter, FuzzCaseKind.ENUM_OUTSIDER, "testforge-not-in-enum");
            // an enum says everything about the value space; boundary cases on
            // top of it would only be noise
            return cases;
        }

        if (type == null || "string".equals(type)) {
            cases.addAll(stringCases(operation, parameter, schema));
        } else if ("integer".equals(type) || "number".equals(type)) {
            cases.addAll(numericCases(operation, parameter, schema, "integer".equals(type)));
        } else if ("boolean".equals(type)) {
            add(cases, operation, parameter, FuzzCaseKind.WRONG_TYPE, "testforge");
        }

        return cases;
    }

    private List<FuzzCase> stringCases(ExplorableOperation operation, Parameter parameter, Schema<?> schema) {
        List<FuzzCase> cases = new ArrayList<>();
        Integer minLength = schema == null ? null : schema.getMinLength();
        Integer maxLength = schema == null ? null : schema.getMaxLength();

        add(cases, operation, parameter, FuzzCaseKind.EMPTY_STRING, "");
        add(cases, operation, parameter, FuzzCaseKind.ENCODING_PROBE, ENCODING_PROBE);
        add(cases, operation, parameter, FuzzCaseKind.UNICODE, UNICODE);

        if (maxLength != null && maxLength > 0) {
            add(cases, operation, parameter, FuzzCaseKind.AT_UPPER_BOUND, "a".repeat(maxLength));
            add(cases, operation, parameter, FuzzCaseKind.TOO_LONG, "a".repeat(maxLength + 1));
        } else {
            add(cases, operation, parameter, FuzzCaseKind.TOO_LONG, "a".repeat(LONG_STRING));
        }
        if (minLength != null && minLength > 0) {
            add(cases, operation, parameter, FuzzCaseKind.AT_LOWER_BOUND, "a".repeat(minLength));
            add(cases, operation, parameter, FuzzCaseKind.TOO_SHORT, "a".repeat(minLength - 1));
        }
        if (schema != null && schema.getPattern() != null) {
            add(cases, operation, parameter, FuzzCaseKind.PATTERN_VIOLATION, "testforge-pattern-violation");
        }

        String format = Schemas.format(schema);
        if (format != null) {
            add(cases, operation, parameter, FuzzCaseKind.FORMAT_VIOLATION, formatViolation(format));
        }
        return cases;
    }

    private List<FuzzCase> numericCases(ExplorableOperation operation, Parameter parameter,
                                        Schema<?> schema, boolean integer) {
        List<FuzzCase> cases = new ArrayList<>();
        BigDecimal minimum = schema == null ? null : schema.getMinimum();
        BigDecimal maximum = schema == null ? null : schema.getMaximum();

        add(cases, operation, parameter, FuzzCaseKind.WRONG_TYPE, "testforge");
        add(cases, operation, parameter, FuzzCaseKind.NEGATIVE, "-1");
        if (integer) {
            add(cases, operation, parameter, FuzzCaseKind.FRACTIONAL_FOR_INTEGER, "1.5");
        }

        if (minimum != null) {
            add(cases, operation, parameter, FuzzCaseKind.AT_LOWER_BOUND, minimum.toPlainString());
            add(cases, operation, parameter, FuzzCaseKind.BELOW_MINIMUM,
                    minimum.subtract(BigDecimal.ONE).toPlainString());
        }
        if (maximum != null) {
            add(cases, operation, parameter, FuzzCaseKind.AT_UPPER_BOUND, maximum.toPlainString());
            add(cases, operation, parameter, FuzzCaseKind.ABOVE_MAXIMUM,
                    maximum.add(BigDecimal.ONE).toPlainString());
        } else {
            add(cases, operation, parameter, FuzzCaseKind.ABOVE_MAXIMUM, Long.toString(Long.MAX_VALUE));
        }
        return cases;
    }

    private String formatViolation(String format) {
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

    private void add(List<FuzzCase> cases, ExplorableOperation operation, Parameter parameter,
                     FuzzCaseKind kind, String value) {
        cases.add(FuzzCase.of(operation.specId(), operation.operationId(), operation.key(),
                parameter.getName(), parameter.getIn(), kind, value));
    }
}
