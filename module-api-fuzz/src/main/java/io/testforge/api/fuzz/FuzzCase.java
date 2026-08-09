package io.testforge.api.fuzz;

/**
 * One mutation of one parameter of one operation.
 *
 * <p>Exactly one parameter is changed per case; every other value stays the
 * one the explorer would normally send. That is what makes a finding
 * attributable — "this endpoint returns 500" is a bug report nobody can act on,
 * "this endpoint returns 500 when {@code taskId} is longer than its declared
 * maximum" is a fix.
 *
 * <p>{@code id} is stable across runs and is the unit of reproduction: put it
 * in {@code forge.api-fuzz.only-cases} and the run repeats that request and
 * nothing else.
 */
public record FuzzCase(
        String id,
        String specId,
        String operationId,
        String operationKey,
        String parameterName,
        String in,
        FuzzCaseKind kind,
        String value,
        boolean omitted) {

    public static FuzzCase of(String specId, String operationId, String operationKey,
                              String parameterName, String in, FuzzCaseKind kind, String value) {
        return new FuzzCase(id(operationId, in, parameterName, kind), specId, operationId, operationKey,
                parameterName, in, kind, value, false);
    }

    public static FuzzCase omitting(String specId, String operationId, String operationKey,
                                    String parameterName, String in) {
        return new FuzzCase(id(operationId, in, parameterName, FuzzCaseKind.OMITTED_REQUIRED), specId,
                operationId, operationKey, parameterName, in, FuzzCaseKind.OMITTED_REQUIRED, null, true);
    }

    public FuzzExpectation expectation() {
        return kind.expectation();
    }

    /** Readable and stable: {@code getTask/path:taskId/TOO_LONG}. */
    private static String id(String operationId, String in, String parameterName, FuzzCaseKind kind) {
        return "%s/%s:%s/%s".formatted(operationId, in, parameterName, kind);
    }
}
