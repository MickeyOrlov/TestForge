package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.http.Redactor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Observations are the artifact that leaves the machine. Whatever is not
 * masked here is masked nowhere.
 */
class ObservationFactoryTest {

    private final ObservationFactory factory = new ObservationFactory(
            new Redactor(new ObjectMapper(), List.of("authorization", "x-api-key"),
                    List.of("token", "password")),
            60);

    @Test
    void credentialHeadersAreMaskedOnBothSides() {
        ApiObservation observation = executed(new RuntimeExchange(
                Map.of("Authorization", "Bearer super-secret", "X-Tenant", "demo"),
                null,
                200,
                "application/json",
                Map.of("X-Api-Key", "live-key"),
                "{}",
                5L,
                null));

        assertThat(observation.requestHeaders())
                .containsEntry("Authorization", "***")
                .containsEntry("X-Tenant", "demo");
        assertThat(observation.responseHeaders()).containsEntry("X-Api-Key", "***");
    }

    @Test
    void credentialFieldsInsideBodiesAreMasked() {
        ApiObservation observation = executed(new RuntimeExchange(
                Map.of(), null, 200, "application/json", Map.of(),
                "{\"user\":\"demo\",\"token\":\"swordfish\"}", 5L, null));

        assertThat(observation.responseBody())
                .contains("demo")
                .doesNotContain("swordfish")
                .contains("***");
    }

    @Test
    void longBodiesAreTruncatedWithTheOriginalLengthKept() {
        String body = "{\"note\":\"" + "x".repeat(500) + "\"}";

        ApiObservation observation = executed(new RuntimeExchange(
                Map.of(), null, 200, "application/json", Map.of(), body, 5L, null));

        assertThat(observation.responseBody())
                .hasSizeLessThan(body.length())
                .contains("truncated")
                .contains(String.valueOf(body.length()));
    }

    @Test
    void aParameterNamedLikeACredentialIsMaskedToo() {
        // a configured API key passed as a query parameter is still a credential
        PlannedRequest plan = PlannedRequest.of(
                new PreparedRequest("GET", "/tasks", Map.of(), Map.of("token", "swordfish")),
                List.of(new ParameterBinding("token", "query", ValueSource.CONFIGURED, "swordfish"),
                        new ParameterBinding("limit", "query", ValueSource.DEFAULT, "25")));

        ApiObservation observation = factory.executed(
                ExplorerFixtures.operation("listTasks"), "https://api.example.test", plan,
                new RuntimeExchange(Map.of(), null, 200, "application/json", Map.of(), "{}", 5L, null),
                List.of());

        assertThat(observation.parameters())
                .extracting(ParameterBinding::name, ParameterBinding::value)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("token", "***"),
                        org.assertj.core.api.Assertions.tuple("limit", "25"));
    }

    @Test
    void aFailedCallRecordsTheReasonAndNoStatus() {
        ApiObservation observation = executed(
                RuntimeExchange.failed(Map.of(), "java.net.ConnectException: Connection refused", 3L));

        assertThat(observation.outcome()).isEqualTo(ExplorerOutcome.FAILED);
        assertThat(observation.status()).isNull();
        assertThat(observation.reason()).contains("Connection refused");
    }

    @Test
    void aSkippedOperationKeepsItsIdentityAndReason() {
        ApiObservation observation = factory.skipped(
                ExplorerFixtures.operation("createTask"),
                "https://api.example.test",
                PlannedRequest.skip(SkipReason.REQUEST_BODY_REQUIRED, "v1 does not synthesize bodies", List.of()));

        assertThat(observation.outcome()).isEqualTo(ExplorerOutcome.SKIPPED);
        assertThat(observation.key()).isEqualTo("POST /tasks");
        assertThat(observation.resolvedUrl()).isEqualTo("https://api.example.test/tasks");
        assertThat(observation.reason()).contains("requires a request body");
    }

    private ApiObservation executed(RuntimeExchange exchange) {
        PlannedRequest plan = PlannedRequest.of(
                new PreparedRequest("GET", "/tasks", Map.of(), Map.of()), List.of());
        return factory.executed(ExplorerFixtures.operation("listTasks"), "https://api.example.test",
                plan, exchange, List.of());
    }
}
