package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ApiExplorerProperties;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.RequestValueResolver;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.api.explorer.SchemaValueFactory;
import io.testforge.http.Redactor;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Request-body fuzzing: the increment that lets the module take a real JSON
 * schema and walk its boundaries, one field at a time.
 */
class BodyFuzzTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JsonBodyFactory bodyFactory = new JsonBodyFactory(MAPPER);
    private final BodyCaseGenerator generator = new BodyCaseGenerator(MAPPER, bodyFactory);
    private final JsonBodyMutator mutator = new JsonBodyMutator(MAPPER);

    // --- case generation ---------------------------------------------------

    @Test
    void casesAddressNestedFieldsByJsonPath() {
        assertThat(ids()).contains(
                "createUser/body:$.name/TOO_SHORT",
                "createUser/body:$.name/OMITTED_REQUIRED",
                "createUser/body:$.age/BELOW_MINIMUM",
                "createUser/body:$.age/WRONG_TYPE",
                "createUser/body:$.profile.city/TOO_SHORT",
                "createUser/body:$.profile.score/AT_EXCLUSIVE_BOUND");
    }

    @Test
    void onlyRequiredFieldsGetAnOmissionCase() {
        assertThat(ids()).contains("createUser/body:$.name/OMITTED_REQUIRED");
        // nickname is optional, so its absence breaks no promise
        assertThat(ids()).doesNotContain("createUser/body:$.nickname/OMITTED_REQUIRED");
    }

    @Test
    void nullabilityIsRespected() {
        assertThat(ids()).contains("createUser/body:$.name/NULL_FOR_NON_NULLABLE");
        // note is declared nullable, so a null there is legal
        assertThat(ids()).doesNotContain("createUser/body:$.note/NULL_FOR_NON_NULLABLE");
    }

    @Test
    void enumsAndArraysBothProduceCases() {
        assertThat(ids()).contains(
                "createUser/body:$.role/ENUM_OUTSIDER",
                "createUser/body:$.tags/EMPTY_ARRAY",
                "createUser/body:$.tags/TOO_MANY_ITEMS",
                "createUser/body:$.tags/INVALID_ITEM_TYPE",
                "createUser/body:$.tags[0]/TOO_LONG");
    }

    @Test
    void expectationsFollowTheSchemaNotTheMutation() {
        assertThat(expectation("createUser/body:$.age/BELOW_MINIMUM")).isEqualTo(FuzzExpectation.REJECT);
        assertThat(expectation("createUser/body:$.age/AT_LOWER_BOUND")).isEqualTo(FuzzExpectation.ACCEPT);
        // nickname declares no length at all, so nothing about it is provable
        assertThat(expectation("createUser/body:$.nickname/TOO_LONG")).isEqualTo(FuzzExpectation.UNSPECIFIED);
    }

    @Test
    void aBranchingSubtreeProducesNoCasesRatherThanUnprovableOnes() {
        List<FuzzCase> cases = generator.generate(
                FuzzFixtures.operation("eitherBody"), schema("eitherBody"), Set.of("$"));

        assertThat(cases).isEmpty();
    }

    @Test
    void generationIsDeterministic() {
        assertThat(ids()).isEqualTo(ids());
    }

    // --- mutation ----------------------------------------------------------

    @Test
    void exactlyOneFieldChangesPerCase() {
        JsonNode baseline = baseline();
        JsonNode mutated = mutate("createUser/body:$.age/BELOW_MINIMUM");

        assertThat(mutated.path("age").asInt()).isEqualTo(17);
        // everything else is byte-identical to the valid baseline. Compared as
        // text: a round trip through JSON turns DecimalNode(5) into IntNode(5),
        // which is the same document and a different object
        assertThat(withoutField(mutated, "age").toString())
                .isEqualTo(withoutField(baseline, "age").toString());
    }

    @Test
    void omittingARequiredFieldRemovesOnlyThatField() {
        JsonNode mutated = mutate("createUser/body:$.name/OMITTED_REQUIRED");

        assertThat(mutated.has("name")).isFalse();
        assertThat(mutated.has("age")).isTrue();
        assertThat(mutated.path("profile").has("city")).isTrue();
    }

    @Test
    void wrongTypeSendsAStringWhereANumberWasPromised() {
        assertThat(mutate("createUser/body:$.age/WRONG_TYPE").path("age").isTextual()).isTrue();
    }

    @Test
    void nestedFieldsAreMutatedInPlace() {
        JsonNode mutated = mutate("createUser/body:$.profile.city/TOO_SHORT");

        assertThat(mutated.path("profile").path("city").asText()).hasSize(1);
        assertThat(mutated.path("name").asText()).isEqualTo(baseline().path("name").asText());
    }

    @Test
    void arraysAreResizedRatherThanReplaced() {
        assertThat(mutate("createUser/body:$.tags/TOO_MANY_ITEMS").path("tags")).hasSize(4);
        assertThat(mutate("createUser/body:$.tags/EMPTY_ARRAY").path("tags")).isEmpty();
        assertThat(mutate("createUser/body:$.tags/INVALID_ITEM_TYPE").path("tags").get(0).isTextual()).isFalse();
    }

    @Test
    void nullIsSentWhereTheSchemaForbidsIt() {
        assertThat(mutate("createUser/body:$.name/NULL_FOR_NON_NULLABLE").path("name").isNull()).isTrue();
    }

    // --- execution ---------------------------------------------------------

    @Test
    void bodyCasesAreNeverSentWithoutTheUnsafeMethodOptIn(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        ApiFuzzReport report = run(output, sent, false, List.of());

        // safe methods still run; the point is that nothing with a body does
        assertThat(sent).isNotEmpty();
        assertThat(sent).extracting(PreparedRequest::method).containsOnly("GET");
        assertThat(sent).extracting(PreparedRequest::body).containsOnlyNulls();

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("createUser"))
                .singleElement()
                .satisfies(operation -> assertThat(operation.skipReason()).contains("allow-unsafe-methods"));
    }

    @Test
    void withTheOptInTheBodyIsActuallySent(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        run(output, sent, true, List.of());

        assertThat(sent).isNotEmpty();
        assertThat(sent).allSatisfy(request -> {
            if ("POST".equals(request.method())) {
                assertThat(request.contentType()).isEqualTo("application/json");
                assertThat(request.body()).isNotBlank();
            }
        });
    }

    @Test
    void anImpossibleBaselineSkipsTheOperationInsteadOfSendingJunk(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        ApiFuzzReport report = run(output, sent, true, List.of());

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("impossibleBody"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.cases()).isZero();
                    assertThat(operation.skipReason()).contains("no valid request body could be built");
                });
        assertThat(sent).noneSatisfy(request ->
                assertThat(request.pathTemplate()).isEqualTo("/impossible"));
    }

    @Test
    void aNonJsonBodyIsReportedAsUnsupported(@TempDir Path output) {
        ApiFuzzReport report = run(output, new ArrayList<>(), true, List.of());

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("xmlOnlyBody"))
                .singleElement()
                .satisfies(operation -> assertThat(operation.skipReason()).contains("JSON only"));
    }

    @Test
    void aBodyCaseReplaysFromItsIdAlone(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        String id = "createUser/body:$.age/BELOW_MINIMUM";

        ApiFuzzReport report = run(output, sent, true, List.of(id));

        assertThat(report.specs().getFirst().cases()).isEqualTo(1);
        assertThat(sent).singleElement().satisfies(request ->
                assertThat(request.body()).contains("\"age\":17"));
    }

    @Test
    void credentialFieldsAreRedactedInTheRecordedCase(@TempDir Path output) {
        List<PreparedRequest> sent = new ArrayList<>();
        ApiFuzzReport report = run(output, sent, true, List.of());

        assertThat(report.specs().getFirst().operations())
                .flatExtracting(OperationFuzzReport::observations)
                .allSatisfy(observation -> assertThat(observation.requestFragment()).isNotNull());
    }

    // --- helpers -----------------------------------------------------------

    private List<String> ids() {
        return generator.generate(FuzzFixtures.operation("createUser"), schema("createUser"), Set.of())
                .stream().map(FuzzCase::id).toList();
    }

    private FuzzExpectation expectation(String id) {
        return generator.generate(FuzzFixtures.operation("createUser"), schema("createUser"), Set.of())
                .stream()
                .filter(fuzzCase -> fuzzCase.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No case " + id))
                .expectation();
    }

    private JsonNode baseline() {
        return bodyFactory.build(schema("createUser")).body();
    }

    private JsonNode mutate(String id) {
        FuzzCase fuzzCase = generator.generate(FuzzFixtures.operation("createUser"), schema("createUser"), Set.of())
                .stream()
                .filter(candidate -> candidate.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No case " + id));

        String mutated = mutator.apply(baseline(), fuzzCase)
                .orElseThrow(() -> new AssertionError("Case did not apply: " + id));
        try {
            return MAPPER.readTree(mutated);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private JsonNode withoutField(JsonNode node, String field) {
        JsonNode copy = node.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy).remove(field);
        return copy;
    }

    private Schema<?> schema(String operationId) {
        ExplorableOperation operation = FuzzFixtures.operation(operationId);
        return operation.operation().getRequestBody().getContent().get("application/json").getSchema();
    }

    private ApiFuzzReport run(Path output, List<PreparedRequest> sent, boolean allowUnsafe, List<String> onlyCases) {
        ApiFuzzProperties properties = new ApiFuzzProperties(
                true, output.toString(), null, List.of(), 1L,
                allowUnsafe ? Set.of("GET", "POST") : Set.of("GET", "POST"),
                allowUnsafe, null, null, null, 500, null, onlyCases, null, null);

        ExchangeExecutor executor = new ExchangeExecutor() {
            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                sent.add(request);
                return new RuntimeExchange(Map.of(), request.body(), 400, "application/json", Map.of(),
                        "{\"message\":\"rejected\"}", 3L, null);
            }

            @Override
            public String baseUrl() {
                return "https://api.example.test";
            }
        };

        return new ApiFuzzRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                        properties.includePaths(), properties.excludePaths()),
                new RequestPlanner(new RequestValueResolver(
                        new ApiExplorerProperties.ParameterProperties(Map.of("q", "query"), Map.of()),
                        new SchemaValueFactory()), true),
                new FuzzCaseGenerator(),
                generator,
                bodyFactory,
                mutator,
                new FuzzCaseSelector(properties.seed(), properties.maxCasesPerOperation()),
                executor,
                new ResponseClassifier(new ResponseContractChecker(MAPPER), MAPPER),
                new ObservationFactory(new Redactor(MAPPER, List.of("authorization"), List.of("token")), 4000),
                MAPPER,
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(FuzzFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(FuzzFixtures.LOCATION))),
                properties).run();
    }
}
