package io.testforge.api.fuzz;

/**
 * One mutation of one field of one operation.
 *
 * <p>Exactly one thing changes per case — one parameter, or one JSON path
 * inside the request body. Everything else keeps the value the baseline would
 * have sent. That is what makes a finding attributable: "this endpoint returns
 * 500" is a bug report nobody can act on, "this endpoint returns 500 when
 * {@code $.profile.age} is below its declared minimum" is a fix.
 *
 * <p>{@code expectation} lives on the case rather than on the kind, because
 * only the case knows whether the document actually forbade the value it is
 * sending.
 *
 * <p>{@code id} is stable across runs and is the unit of reproduction: put it
 * in {@code forge.api-fuzz.only-cases} and the run repeats that request alone.
 */
public record FuzzCase(
        String id,
        String specId,
        String operationId,
        String operationKey,
        String parameterName,
        String in,
        FuzzCaseKind kind,
        FuzzExpectation expectation,
        String value,
        boolean omitted) {

    /** Where a body case's {@code in} sits, next to {@code path} and {@code query}. */
    public static final String BODY = "body";

    public static FuzzCase parameter(String specId, String operationId, String operationKey,
                                     String parameterName, String in, FuzzCaseKind kind,
                                     FuzzExpectation expectation, String value) {
        return new FuzzCase(id(operationId, in, parameterName, kind), specId, operationId, operationKey,
                parameterName, in, kind, expectation, value, false);
    }

    /** {@code parameterName} is a JSON path into the body, such as {@code $.profile.age}. */
    public static FuzzCase body(String specId, String operationId, String operationKey,
                                String jsonPath, FuzzCaseKind kind,
                                FuzzExpectation expectation, String value) {
        return new FuzzCase(id(operationId, BODY, jsonPath, kind), specId, operationId, operationKey,
                jsonPath, BODY, kind, expectation, value, false);
    }

    public static FuzzCase omitting(String specId, String operationId, String operationKey,
                                    String name, String in) {
        return new FuzzCase(id(operationId, in, name, FuzzCaseKind.OMITTED_REQUIRED), specId,
                operationId, operationKey, name, in, FuzzCaseKind.OMITTED_REQUIRED,
                FuzzExpectation.REJECT, null, true);
    }

    public boolean bodyCase() {
        return BODY.equals(in);
    }

    /** {@code createUser/body:$.profile.age/BELOW_MINIMUM}. */
    private static String id(String operationId, String in, String name, FuzzCaseKind kind) {
        return "%s/%s:%s/%s".formatted(operationId, in, name, kind);
    }
}
