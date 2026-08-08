package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Turning a response into a verdict — including the one a generic fuzzer cannot
 * reach: the service accepted something its own document forbids.
 */
class ResponseClassifierTest {

    private final ResponseClassifier classifier =
            new ResponseClassifier(new ResponseContractChecker(new ObjectMapper()), new ObjectMapper());
    private final ExplorableOperation getItem = FuzzFixtures.operation("getItem");

    @Test
    void rejectingAnInvalidValueIsTheExpectedOutcome() {
        assertThat(classify(FuzzCaseKind.TOO_LONG, FuzzExpectation.REJECT, "aaaaaaaaa", 400, "{\"message\":\"too long\"}").verdict())
                .isEqualTo(FuzzVerdict.PASSED);
    }

    @Test
    void acceptingAnInvalidValueIsAFinding() {
        assertThat(classify(FuzzCaseKind.TOO_LONG, FuzzExpectation.REJECT, "aaaaaaaaa", 200, "{\"id\":\"x\"}").verdict())
                .isEqualTo(FuzzVerdict.OVER_PERMISSIVE);
    }

    @Test
    void aServerErrorOutranksEverythingElse() {
        assertThat(classify(FuzzCaseKind.TOO_LONG, FuzzExpectation.REJECT, "aaaaaaaaa", 500, "boom").verdict())
                .isEqualTo(FuzzVerdict.SERVER_ERROR);
    }

    @Test
    void anUndocumentedStatusIsReportedWhenNothingWorseHappened() {
        assertThat(classify(FuzzCaseKind.EMPTY_STRING, FuzzExpectation.UNSPECIFIED, "", 418, "{}").verdict())
                .isEqualTo(FuzzVerdict.UNDOCUMENTED_RESPONSE);
    }

    @Test
    void aValueEchoedBackVerbatimIsReported() {
        ResponseClassifier.Classification classification =
                classify(FuzzCaseKind.ENCODING_PROBE, FuzzExpectation.UNSPECIFIED, "tf'\"<>&", 400,
                        "{\"message\":\"bad value tf'\\\"<>&\"}");

        assertThat(classification.verdict()).isEqualTo(FuzzVerdict.INPUT_REFLECTED);
        assertThat(classification.inputReflected()).isTrue();
    }

    @Test
    void refusingAValidBoundaryIsOverStrict() {
        assertThat(classify(FuzzCaseKind.AT_UPPER_BOUND, FuzzExpectation.ACCEPT, "aaaaaaaa", 400, "{\"message\":\"nope\"}").verdict())
                .isEqualTo(FuzzVerdict.OVER_STRICT);
    }

    @Test
    void aDroppedConnectionIsATransportFailure() {
        ResponseClassifier.Classification classification = classifier.classify(
                getItem,
                FuzzCase.parameter("demo", "getItem", "GET /items/{itemId}", "itemId", "path",
                        FuzzCaseKind.TOO_LONG, FuzzExpectation.REJECT, "aaaaaaaaa"),
                RuntimeExchange.failed(Map.of(), "java.net.SocketTimeoutException", 30_000L));

        assertThat(classification.verdict()).isEqualTo(FuzzVerdict.TRANSPORT_FAILURE);
    }

    @Test
    void aShortValueIsNotTreatedAsReflectionByCoincidence() {
        // "ab" appearing in a response means nothing
        ResponseClassifier.Classification classification =
                classify(FuzzCaseKind.TOO_SHORT, FuzzExpectation.REJECT, "a", 400, "{\"message\":\"a is too short\"}");

        assertThat(classification.inputReflected()).isFalse();
    }

    private ResponseClassifier.Classification classify(FuzzCaseKind kind, FuzzExpectation expectation,
                                                       String value, int status, String body) {
        FuzzCase fuzzCase = FuzzCase.parameter("demo", "getItem", "GET /items/{itemId}", "itemId", "path",
                kind, expectation, value);
        RuntimeExchange exchange = new RuntimeExchange(
                Map.of(), null, status, "application/json", Map.of(), body, 5L, null);
        return classifier.classify(getItem, fuzzCase, exchange);
    }
}
