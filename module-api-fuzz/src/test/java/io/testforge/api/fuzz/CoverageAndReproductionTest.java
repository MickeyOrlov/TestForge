package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.api.explorer.ApiExplorerProperties;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.ObservationFactory;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RequestPlanner;
import io.testforge.api.explorer.RequestValueResolver;
import io.testforge.api.explorer.ResponseContractChecker;
import io.testforge.api.explorer.RuntimeExchange;
import io.testforge.api.explorer.SafetyPolicy;
import io.testforge.http.Redactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the run tested, and what it would take to come back to a finding later.
 */
class CoverageAndReproductionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void coverageListsWhatWasTestedAndWhatWasNot(@TempDir Path output) {
        ConstraintCoverage coverage = coverageOf(run(output, "createUser"));

        assertThat(coverage.declared()).isNotEmpty();
        assertThat(coverage.exercised()).extracting(DeclaredConstraint::toString)
                .contains("$.age minimum", "$.age maximum", "$.name minLength",
                        "$.name maxLength", "$.role enum", "$.name required");
        assertThat(coverage.declared())
                .containsAll(coverage.exercised())
                .containsAll(coverage.unexercised());
        assertThat(coverage.exercised()).doesNotContainAnyElementsOf(coverage.unexercised());
    }

    @Test
    void aConstraintNothingCouldTestIsListedAsUnexercised(@TempDir Path output) {
        // score declares multipleOf and an exclusive minimum; the run should be
        // explicit about which of those it actually reached
        ConstraintCoverage coverage = coverageOf(run(output, "createUser"));

        assertThat(coverage.declared()).extracting(DeclaredConstraint::toString)
                .contains("$.profile.score multipleOf", "$.profile.score exclusiveMinimum");
    }

    @Test
    void coverageIsReportedEvenWhenTheControlWasRefused(@TempDir Path output) {
        // the operation was never fuzzed, but a reader still needs to know what
        // the document promised
        ApiFuzzReport report = run(output, "createUser", 401);

        assertThat(report.specs().getFirst().operations())
                .filteredOn(operation -> operation.operationId().equals("createUser"))
                .singleElement()
                .satisfies(operation -> {
                    assertThat(operation.control().outcome()).isEqualTo(ControlOutcome.BLOCKED);
                    assertThat(operation.coverage().declared()).isNotEmpty();
                    assertThat(operation.coverage().exercised()).isEmpty();
                });
    }

    @Test
    void everyFindingGetsAManifestThatNamesTheDocumentItWasMadeAgainst(@TempDir Path output) throws IOException {
        ApiFuzzReport report = run(output, "createUser");
        List<ReproductionManifest> manifests = report.specs().getFirst().reproduction();

        assertThat(manifests).isNotEmpty();
        assertThat(manifests).allSatisfy(manifest -> {
            assertThat(manifest.specFingerprint()).startsWith("sha256:");
            assertThat(manifest.seed()).isEqualTo(20260101L);
            assertThat(manifest.caseId()).isNotBlank();
            assertThat(manifest.expectation()).isNotNull();
            // every finding records the control it was interpreted against
            assertThat(manifest.controlOutcome()).isEqualTo(ControlOutcome.ACCEPTED);
            assertThat(manifest.controlStatus()).isNotNull();
        });

        assertThat(manifests)
                .filteredOn(manifest -> manifest.operationId().equals("createUser"))
                .allSatisfy(manifest -> assertThat(manifest.controlStatus()).isEqualTo(201));

        assertThat(Files.readString(output.resolve("demo").resolve("reproduction.json")))
                .contains("specFingerprint");
    }

    @Test
    void theFingerprintChangesWhenTheDocumentDoes(@TempDir Path output) {
        String fingerprint = run(output, "createUser").specs().getFirst().fingerprint();

        assertThat(fingerprint).isEqualTo(run(output, "createUser").specs().getFirst().fingerprint());
        assertThat(fingerprint).isNotEqualTo(SpecFingerprint.of(
                new OpenApiSpecParser().parse(
                        new io.testforge.api.discovery.ApiSpecSource("other", "classpath:openapi/fuzz-demo.yaml"))
                        .info(null)));
    }

    @Test
    void manifestsCarryNoSecrets(@TempDir Path output) throws IOException {
        run(output, "createUser");

        String manifests = Files.readString(output.resolve("demo").resolve("reproduction.json"));

        assertThat(manifests).doesNotContain("swordfish", "Bearer");
    }

    @Test
    void artifactsAreIdenticalBetweenRunsOfTheSameSpecAndSeed(@TempDir Path first, @TempDir Path second)
            throws IOException {
        run(first, "createUser");
        run(second, "createUser");

        assertThat(Files.readString(second.resolve("demo").resolve("createuser.json")))
                .isEqualTo(Files.readString(first.resolve("demo").resolve("createuser.json")));
    }

    private ConstraintCoverage coverageOf(ApiFuzzReport report) {
        return report.specs().getFirst().operations().stream()
                .filter(operation -> operation.operationId().equals("createUser"))
                .findFirst()
                .orElseThrow()
                .coverage();
    }

    private ApiFuzzReport run(Path output, String operationId) {
        return run(output, operationId, 201);
    }

    /** {@code controlStatus} is what a valid request gets; mutations always get 400. */
    private ApiFuzzReport run(Path output, String operationId, int controlStatus) {
        ApiFuzzProperties properties = new ApiFuzzProperties(
                true, output.toString(), null, List.of(), 20260101L, Set.of("GET", "POST"), true,
                null, null, null, 500, null, 0, false, 0, List.of(), null, null);

        ExchangeExecutor executor = new ExchangeExecutor() {
            private boolean controlSent;

            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                if (!"POST".equals(request.method()) || !"/users".equals(request.pathTemplate())) {
                    return json(200, "{\"id\":\"x\"}");
                }
                if (!controlSent) {
                    controlSent = true;
                    return json(controlStatus, "{}");
                }
                return json(400, "{\"message\":\"rejected\"}");
            }

            @Override
            public String baseUrl() {
                return "https://api.example.test";
            }

            private RuntimeExchange json(int status, String body) {
                return new RuntimeExchange(Map.of(), null, status, "application/json", Map.of(), body, 3L, null);
            }
        };

        JsonBodyFactory bodyFactory = new JsonBodyFactory(MAPPER);
        ResponseClassifier classifier = new ResponseClassifier(new ResponseContractChecker(MAPPER), MAPPER);
        FindingConfirmer confirmer = new FindingConfirmer(executor, classifier, properties);
        RequestShrinker shrinker = new RequestShrinker(MAPPER, new ConstraintInventory(bodyFactory),
                bodyFactory, confirmer, properties);
        ObjectMapper objectMapper = MAPPER;

        return new ApiFuzzRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                        properties.includePaths(), properties.excludePaths()),
                new RequestPlanner(new RequestValueResolver(
                        new ApiExplorerProperties.ParameterProperties(Map.of("q", "query"), Map.of()),
                        new ConstraintAwareValueFactory()), true),
                new FuzzCaseGenerator(),
                new BodyCaseGenerator(MAPPER, bodyFactory),
                bodyFactory,
                new JsonBodyMutator(MAPPER),
                new ConstraintInventory(bodyFactory),
                new BaselineSelfCheck(),
                confirmer,
                shrinker,
                new ReproductionWriter(objectMapper),
                new FuzzCaseSelector(properties.seed(), properties.maxCasesPerOperation()),
                executor,
                classifier,
                new ObservationFactory(new Redactor(MAPPER, List.of("authorization"), List.of("token")), 4000),
                MAPPER,
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(FuzzFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(FuzzFixtures.LOCATION))),
                properties).run();
    }
}
