package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RuntimeExchange;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cases against the request envelope: broken JSON, a media type the operation
 * never declared, a body where one is required.
 *
 * <p>They are kept apart from schema mutations everywhere it matters — in the
 * category on the kind, in the coverage counts, and in the constraint each case
 * claims to exercise, which for these is none.
 */
class ProtocolMutationTest {

    @Test
    void theEnvelopeIsBrokenOneWayAtATime(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        run(output, sent, true);

        List<PreparedRequest> posts = sent.stream()
                .filter(request -> "POST".equals(request.method()) && "/users".equals(request.pathTemplate()))
                .toList();

        assertThat(posts).anySatisfy(request -> {
            assertThat(request.body()).isEqualTo(ProtocolCaseGenerator.MALFORMED_JSON);
            assertThat(request.contentType()).isEqualTo("application/json");
        });
        assertThat(posts).anySatisfy(request -> {
            // the body stays valid: only the media type is wrong
            assertThat(request.contentType()).isEqualTo(ProtocolCaseGenerator.FOREIGN_CONTENT_TYPE);
            assertThat(request.body()).contains("\"name\"");
        });
        assertThat(posts).anySatisfy(request -> assertThat(request.body()).isEmpty());
    }

    @Test
    void protocolCasesExerciseNoDeclaredConstraint(@TempDir Path output) {
        ApiFuzzReport report = run(output, new ArrayList<>(), true);

        List<FuzzObservation> protocolCases = observations(report).stream()
                .filter(observation -> observation.fuzzCase().protocolCase())
                .toList();

        assertThat(protocolCases).isNotEmpty();
        assertThat(protocolCases).allSatisfy(observation -> {
            assertThat(observation.fuzzCase().kind().category())
                    .isEqualTo(FuzzCaseCategory.PROTOCOL_MUTATION);
            // a constraint here would inflate coverage with work that tested
            // nothing the document promised
            assertThat(observation.fuzzCase().constraint()).isNull();
        });

        MutationOutcomes outcomes = report.specs().getFirst().operations().stream()
                .filter(operation -> "createUser".equals(operation.operationId()))
                .findFirst()
                .orElseThrow()
                .coverage()
                .outcomes();
        assertThat(outcomes.protocolMutations()).isPositive();
        assertThat(outcomes.schemaMutations()).isPositive();
        assertThat(outcomes.total()).isEqualTo(outcomes.protocolMutations() + outcomes.schemaMutations());
    }

    @Test
    void theyCanBeTurnedOffWithoutTouchingAnythingElse(@TempDir Path output) {
        ApiFuzzReport report = run(output, new ArrayList<>(), false);

        assertThat(observations(report)).isNotEmpty();
        assertThat(observations(report)).noneMatch(observation -> observation.fuzzCase().protocolCase());
    }

    @Test
    void aMissingContentTypeTheClientPutsBackIsReportedAsNotApplicable(@TempDir Path output) {
        ApiFuzzReport report = run(output, new ArrayList<>(), true, "application/json");

        assertThat(observations(report))
                .filteredOn(observation -> observation.fuzzCase().kind() == FuzzCaseKind.MISSING_CONTENT_TYPE)
                .singleElement()
                .satisfies(observation -> {
                    // the request never went out the way the case describes, so
                    // scoring the answer would be scoring somebody else's request
                    assertThat(observation.verdict()).isEqualTo(FuzzVerdict.NOT_APPLICABLE);
                    assertThat(observation.reason()).contains("supplied Content-Type: application/json");
                });
    }

    private List<FuzzObservation> observations(ApiFuzzReport report) {
        return report.specs().getFirst().operations().stream()
                .flatMap(operation -> operation.observations().stream())
                .toList();
    }

    private ApiFuzzReport run(Path output, List<PreparedRequest> sent, boolean protocolMutations) {
        return run(output, sent, protocolMutations, null);
    }

    private ApiFuzzReport run(Path output, List<PreparedRequest> sent, boolean protocolMutations,
                              String contentTypeAddedByClient) {

        ApiFuzzProperties properties = new ApiFuzzProperties(
                true, output.toString(), null, List.of(), 1L, Set.of("POST"), true,
                List.of("/users"), null, null, 500, null, 0, false, 0, protocolMutations,
                List.of(), null, null);

        ExchangeExecutor executor = new ExchangeExecutor() {
            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                sent.add(request);
                Map<String, String> headers = request.contentType() != null
                        ? Map.of("Content-Type", request.contentType())
                        : contentTypeAddedByClient == null
                                ? Map.of()
                                : Map.of("Content-Type", contentTypeAddedByClient);

                boolean wellFormed = request.body() != null && request.body().startsWith("{\"name\"");
                int status = wellFormed && "application/json".equals(request.contentType()) ? 201 : 400;
                return new RuntimeExchange(headers, request.body(), status, "application/json", Map.of(),
                        status == 201 ? "{}" : "{\"message\":\"rejected\"}", 3L, null);
            }

            @Override
            public String baseUrl() {
                return "https://api.example.test";
            }
        };

        return FuzzFixtures.runner(properties, executor).run();
    }
}
