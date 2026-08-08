package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Differential classification: what the operation answered with valid data,
 * against what it answered with one field changed.
 *
 * <p>The pair is the point. Reading the mutated response alone — which is what
 * v1.1 did — turns every authentication failure into a passing validation case.
 */
class ResponseClassifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ResponseClassifier classifier =
            new ResponseClassifier(new ResponseContractChecker(MAPPER), MAPPER);
    private final ExplorableOperation getItem = FuzzFixtures.operation("getItem");

    // --- with an accepted control, conclusions are allowed -------------------

    @Test
    void anInvalidValueRefusedAfterAnAcceptedControlIsAPass() {
        assertThat(classify(control(200), FuzzExpectation.REJECT, 400).verdict())
                .isEqualTo(FuzzVerdict.PASSED);
    }

    @Test
    void anInvalidValueAcceptedAfterAnAcceptedControlIsOverPermissive() {
        assertThat(classify(control(200), FuzzExpectation.REJECT, 200).verdict())
                .isEqualTo(FuzzVerdict.OVER_PERMISSIVE);
    }

    @Test
    void aValidBoundaryRefusedAfterAnAcceptedControlIsOverStrict() {
        assertThat(classify(control(200), FuzzExpectation.ACCEPT, 422).verdict())
                .isEqualTo(FuzzVerdict.OVER_STRICT);
    }

    @Test
    void aProbeConcludesNothingAboutValidationEitherWay() {
        assertThat(classify(control(200), FuzzExpectation.UNSPECIFIED, 400).verdict())
                .isEqualTo(FuzzVerdict.PASSED);
        assertThat(classify(control(200), FuzzExpectation.UNSPECIFIED, 200).verdict())
                .isEqualTo(FuzzVerdict.PASSED);
    }

    // --- without one, nothing is ---------------------------------------------

    @Test
    void anUnauthorizedControlMakesEveryCaseInconclusive() {
        // the exact false positive v1.1 produced: 401 to valid data, 401 to
        // invalid data, reported as successful validation
        ResponseClassifier.Classification classification =
                classify(control(401), FuzzExpectation.REJECT, 401);

        assertThat(classification.verdict()).isEqualTo(FuzzVerdict.INCONCLUSIVE);
        assertThat(classification.has(FuzzEvidenceKind.CONTROL_NOT_ACCEPTED)).isTrue();
        assertThat(classification.reason()).contains("BLOCKED");
    }

    @Test
    void aForbiddenControlIsNotValidationEither() {
        assertThat(classify(control(403), FuzzExpectation.REJECT, 400).verdict())
                .isEqualTo(FuzzVerdict.INCONCLUSIVE);
    }

    @Test
    void aRateLimitedControlIsNotValidationEither() {
        assertThat(classify(control(429), FuzzExpectation.REJECT, 400).verdict())
                .isEqualTo(FuzzVerdict.INCONCLUSIVE);
    }

    @Test
    void aRedirectedControlNeverReachedTheHandler() {
        assertThat(ControlResult.of(exchange(302, "{}")).outcome()).isEqualTo(ControlOutcome.BLOCKED);
    }

    @Test
    void aCrashingControlMeansTheEndpointIsBrokenNotStrict() {
        ControlResult control = ControlResult.of(exchange(500, "{}"));

        assertThat(control.outcome()).isEqualTo(ControlOutcome.FAILED);
        assertThat(classify(control, FuzzExpectation.REJECT, 400).verdict())
                .isEqualTo(FuzzVerdict.INCONCLUSIVE);
    }

    @Test
    void aControlRefusedAsInvalidMeansTheBaselineOrTheDocumentIsWrong() {
        ControlResult control = ControlResult.of(exchange(400, "{}"));

        assertThat(control.outcome()).isEqualTo(ControlOutcome.REJECTED);
        assertThat(control.reason()).contains("the document calls valid");
        assertThat(classify(control, FuzzExpectation.REJECT, 400).verdict())
                .isEqualTo(FuzzVerdict.INCONCLUSIVE);
    }

    @Test
    void anUnreachableControlIsRecordedAsSuch() {
        ControlResult control = ControlResult.of(
                RuntimeExchange.failed(Map.of(), "java.net.ConnectException", 10L));

        assertThat(control.outcome()).isEqualTo(ControlOutcome.UNREACHABLE);
        assertThat(control.conclusive()).isFalse();
    }

    // --- infrastructure answers to the mutation ------------------------------

    @Test
    void aMutationBlockedByInfrastructureConcludesNothing() {
        // the control got through and this did not: a gateway or rate limiter
        // answered, so no validation happened
        ResponseClassifier.Classification classification =
                classify(control(200), FuzzExpectation.REJECT, 429);

        assertThat(classification.verdict()).isEqualTo(FuzzVerdict.INCONCLUSIVE);
        assertThat(classification.has(FuzzEvidenceKind.INFRASTRUCTURE_RESPONSE)).isTrue();
    }

    @Test
    void aCrashIsEvidenceButNotAValidationVerdict() {
        ResponseClassifier.Classification classification =
                classify(control(200), FuzzExpectation.REJECT, 500);

        assertThat(classification.has(FuzzEvidenceKind.SERVER_ERROR)).isTrue();
        assertThat(classification.verdict())
                .describedAs("a crash hides what the service would have decided")
                .isEqualTo(FuzzVerdict.INCONCLUSIVE);
    }

    @Test
    void aMutatedRequestThatNeverCompletedIsNotAValidationVerdict() {
        ResponseClassifier.Classification classification = classifier.classify(getItem, control(200),
                fuzzCase(FuzzExpectation.REJECT, "aaaaaaaaa"),
                RuntimeExchange.failed(Map.of(), "java.net.SocketTimeoutException", 30_000L));

        assertThat(classification.verdict()).isEqualTo(FuzzVerdict.INCONCLUSIVE);
        assertThat(classification.has(FuzzEvidenceKind.TRANSPORT_FAILURE)).isTrue();
    }

    // --- evidence survives the verdict ---------------------------------------

    @Test
    void severalIndependentFactsAreAllKept() {
        // an undocumented status that also echoes the value back: v1.1 reported
        // whichever ranked highest and lost the other
        ResponseClassifier.Classification classification = classifier.classify(getItem, control(200),
                fuzzCase(FuzzExpectation.UNSPECIFIED, "tf-long-echo-value"),
                new RuntimeExchange(Map.of(), null, 418, "application/json", Map.of(),
                        "{\"echo\":\"tf-long-echo-value\"}", 4L, null));

        assertThat(classification.evidence())
                .extracting(FuzzEvidence::kind)
                .contains(FuzzEvidenceKind.UNDOCUMENTED_RESPONSE, FuzzEvidenceKind.INPUT_REFLECTED);
    }

    private ResponseClassifier.Classification classify(ControlResult control,
                                                       FuzzExpectation expectation, int status) {
        return classifier.classify(getItem, control, fuzzCase(expectation, "aaaaaaaaa"),
                exchange(status, "{\"message\":\"whatever\"}"));
    }

    private ControlResult control(int status) {
        return ControlResult.of(exchange(status, "{\"id\":\"item-1\"}"));
    }

    private RuntimeExchange exchange(int status, String body) {
        return new RuntimeExchange(Map.of(), null, status, "application/json", Map.of(), body, 4L, null);
    }

    private FuzzCase fuzzCase(FuzzExpectation expectation, String value) {
        return FuzzCase.parameter("demo", "getItem", "GET /items/{itemId}", "itemId", "path",
                FuzzCaseKind.TOO_LONG, expectation, "maxLength", value);
    }
}
