package io.testforge.api.fuzz;

/**
 * One mutation of one field of one operation.
 *
 * <p>Exactly one thing changes per case — one parameter, one element of one, or
 * one JSON path inside the request body. Everything else keeps the value the
 * baseline would have sent. That is what makes a finding attributable: "this
 * endpoint returns 500" is a bug report nobody can act on, "this endpoint
 * returns 500 when {@code $.profile.age} is below its declared minimum" is a
 * fix.
 *
 * <p>{@code parameterName} and {@code location} are deliberately separate.
 * The first is what goes on the wire — a query parameter name, or a JSON path.
 * The second is where the document made the promise being tested, which for an
 * array element is {@code query:tags[0]} even though the whole serialized array
 * is what gets sent. Deriving one from the other collapsed the two array cases
 * of a single parameter onto one id.
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
        String location,
        FuzzCaseKind kind,
        FuzzExpectation expectation,
        String constraint,
        String value,
        boolean omitted) {

    /** Where a body case's {@code in} sits, next to {@code path} and {@code query}. */
    public static final String BODY = "body";

    /** Where a case that breaks the envelope rather than a value sits. */
    public static final String PROTOCOL = "protocol";

    public static FuzzCase parameter(String specId, String operationId, String operationKey,
                                     String parameterName, String in, FuzzCaseKind kind,
                                     FuzzExpectation expectation, String constraint, String value) {
        String location = in + ":" + parameterName;
        return at(specId, operationId, operationKey, parameterName, in, location, location,
                kind, expectation, constraint, value);
    }

    /**
     * A mutation of one element of a serialized array parameter. The whole array
     * still goes out under the parameter's own name; only the reported location
     * narrows to the element.
     */
    public static FuzzCase arrayItem(String specId, String operationId, String operationKey,
                                     String parameterName, String in, FuzzCaseKind kind,
                                     FuzzExpectation expectation, String constraint, String value) {
        String location = in + ":" + parameterName + "[0]";
        return at(specId, operationId, operationKey, parameterName, in, location, location,
                kind, expectation, constraint, value);
    }

    /** {@code parameterName} is a JSON path into the body, such as {@code $.profile.age}. */
    public static FuzzCase body(String specId, String operationId, String operationKey,
                                String jsonPath, FuzzCaseKind kind,
                                FuzzExpectation expectation, String constraint, String value) {
        return at(specId, operationId, operationKey, jsonPath, BODY, BODY + ":" + jsonPath, jsonPath,
                kind, expectation, constraint, value);
    }

    /**
     * A case against the request envelope. It exercises no declared constraint —
     * nothing in the schema promises anything about a missing
     * {@code Content-Type} — so {@code constraint} stays null and coverage stays
     * about the document.
     */
    public static FuzzCase protocol(String specId, String operationId, String operationKey,
                                    FuzzCaseKind kind, FuzzExpectation expectation, String value) {
        return at(specId, operationId, operationKey, PROTOCOL, PROTOCOL, PROTOCOL,
                PROTOCOL + ":" + kind, kind, expectation, null, value);
    }

    public static FuzzCase omitting(String specId, String operationId, String operationKey,
                                    String name, String in) {
        String idPath = in + ":" + name;
        String location = BODY.equals(in) ? name : idPath;
        return new FuzzCase(id(operationId, idPath, FuzzCaseKind.OMITTED_REQUIRED), specId,
                operationId, operationKey, name, in, location, FuzzCaseKind.OMITTED_REQUIRED,
                FuzzExpectation.REJECT, "required", null, true);
    }

    private static FuzzCase at(String specId, String operationId, String operationKey,
                               String parameterName, String in, String idPath, String location,
                               FuzzCaseKind kind, FuzzExpectation expectation,
                               String constraint, String value) {
        return new FuzzCase(id(operationId, idPath, kind), specId, operationId, operationKey,
                parameterName, in, location, kind, expectation, constraint, value, false);
    }

    public boolean bodyCase() {
        return BODY.equals(in);
    }

    public boolean protocolCase() {
        return PROTOCOL.equals(in);
    }

    /** {@code createUser/body:$.profile.age/BELOW_MINIMUM}. */
    private static String id(String operationId, String idPath, FuzzCaseKind kind) {
        return "%s/%s/%s".formatted(operationId, idPath, kind);
    }
}
