package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Confirmation and minimization: turning "this case failed once" into "here is
 * the smallest request that fails, and it failed twice out of two".
 */
class ConfirmationAndShrinkTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String AGE_CASE = "createUser/body:$.age/BELOW_MINIMUM";

    // --- confirmation ---------------------------------------------------------

    @Test
    void confirmationIsOffByDefaultAndCostsNothing(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> 201);
        ApiFuzzReport report = run(output, recorder, options().build());

        assertThat(report.findings()).isNotEmpty();
        assertThat(report.findings()).allSatisfy(finding ->
                assertThat(finding.confirmation().reproducibility()).isEqualTo(Reproducibility.NOT_CONFIRMED));
    }

    @Test
    void aStableCrashIsReportedAsReproducible(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        ApiFuzzReport report = run(output, recorder, options().confirmationRuns(2).allowUnsafeConfirmation(true).build());

        assertThat(finding(report, AGE_CASE).confirmation().summary()).isEqualTo("REPRODUCIBLE (2/2)");
    }

    @Test
    void aFindingThatDoesNotComeBackIsReportedAsDisappeared(@TempDir Path output) {
        Recorder recorder = new Recorder(new OnceThen(500, 201));
        ApiFuzzReport report = run(output, recorder, options().confirmationRuns(2).allowUnsafeConfirmation(true).build());

        assertThat(finding(report, AGE_CASE).confirmation().reproducibility())
                .isEqualTo(Reproducibility.DISAPPEARED);
    }

    @Test
    void anIntermittentCrashIsKeptAndLabelledFlaky(@TempDir Path output) throws IOException {
        // 500, then 201, then 500 — exactly the pattern that gets swept under a
        // rug by anything that only retries until it is happy
        Recorder recorder = new Recorder(new Alternating(500, 201, 500));
        ApiFuzzReport report = run(output, recorder, options().confirmationRuns(2).allowUnsafeConfirmation(true).build());

        FuzzObservation finding = finding(report, AGE_CASE);
        assertThat(finding.confirmation().reproducibility()).isEqualTo(Reproducibility.FLAKY);
        assertThat(finding.flaky()).isTrue();
        assertThat(Files.readString(Path.of(report.reportMarkdown()))).contains("## Flaky findings");
    }

    // --- safety ---------------------------------------------------------------

    @Test
    void writeOperationsAreNotRepeatedWithoutTheirOwnOptIn(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        ApiFuzzReport report = run(output, recorder, options().confirmationRuns(2).shrinkAttempts(10).build());

        FuzzObservation finding = finding(report, AGE_CASE);
        assertThat(finding.confirmation().reproducibility()).isEqualTo(Reproducibility.NOT_ATTEMPTED);
        assertThat(finding.confirmation().reason()).contains("allow-unsafe-confirmation");
        assertThat(finding.shrink().attempted()).isFalse();
    }

    @Test
    void withTheOptInAWriteOperationIsConfirmed(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        ApiFuzzReport report = run(output, recorder,
                options().confirmationRuns(2).allowUnsafeConfirmation(true).build());

        assertThat(finding(report, AGE_CASE).confirmation().reproducibility())
                .isEqualTo(Reproducibility.REPRODUCIBLE);
    }

    // --- shrinking ------------------------------------------------------------

    @Test
    void optionalFieldsAreRemovedWhileTheFindingSurvives(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        ApiFuzzReport report = run(output, recorder, shrinking());

        FuzzObservation finding = finding(report, AGE_CASE);
        assertThat(finding.shrink().attempted()).isTrue();
        assertThat(finding.shrink().reduced()).isTrue();
        assertThat(finding.shrink().removed()).contains("$.nickname");

        JsonNode minimal = parse(finding.shrink().minimalBody());
        assertThat(minimal.has("nickname")).isFalse();
    }

    @Test
    void requiredFieldsAreNeverRemovedAndTheTargetIsPreserved(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        JsonNode minimal = parse(finding(run(output, recorder, shrinking()), AGE_CASE).shrink().minimalBody());

        // name and profile are required; age is the target
        assertThat(minimal.has("name")).isTrue();
        assertThat(minimal.has("profile")).isTrue();
        assertThat(minimal.path("profile").has("city")).isTrue();
        assertThat(minimal.path("age").asInt()).isEqualTo(17);
    }

    @Test
    void nestedOptionalFieldsShrinkToo(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        FuzzObservation finding = finding(run(output, recorder, shrinking()), AGE_CASE);

        // profile.score is optional; profile.city is not
        assertThat(finding.shrink().removed()).contains("$.profile.score");
        assertThat(parse(finding.shrink().minimalBody()).path("profile").has("score")).isFalse();
    }

    @Test
    void arraysShrinkNoFurtherThanTheirDeclaredMinimum(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        JsonNode minimal = parse(finding(run(output, recorder, shrinking()), AGE_CASE).shrink().minimalBody());

        // tags is optional, so it may vanish entirely; if it survives it must
        // still satisfy minItems: 1
        if (minimal.has("tags")) {
            assertThat(minimal.path("tags")).hasSizeGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void shrinkingStopsWhenTheFindingDisappears(@TempDir Path output) {
        // the crash only happens while nickname is present, so no removal can
        // be accepted and the request must come back unshrunk
        Recorder recorder = new Recorder(request ->
                ageIs(request, 17) && body(request).has("nickname") ? 500 : 201);

        FuzzObservation finding = finding(run(output, recorder, shrinking()), AGE_CASE);

        assertThat(finding.shrink().attempted()).isTrue();
        assertThat(finding.shrink().removed()).doesNotContain("$.nickname");
        assertThat(parse(finding.shrink().minimalBody()).has("nickname")).isTrue();
    }

    @Test
    void theAttemptBudgetIsAHardStop(@TempDir Path output) {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        ApiFuzzReport report = run(output, recorder,
                options().allowUnsafeConfirmation(true).shrinkAttempts(2).build());

        assertThat(finding(report, AGE_CASE).shrink().attempts()).isLessThanOrEqualTo(2);
    }

    @Test
    void theSameInputProducesTheSameAttemptsAndTheSameResult(@TempDir Path first, @TempDir Path second)
            throws IOException {
        Recorder one = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        Recorder two = new Recorder(request -> ageIs(request, 17) ? 500 : 201);

        FuzzObservation left = finding(run(first, one, shrinking()), AGE_CASE);
        FuzzObservation right = finding(run(second, two, shrinking()), AGE_CASE);

        assertThat(right.shrink().removed()).isEqualTo(left.shrink().removed());
        assertThat(right.shrink().minimalBody()).isEqualTo(left.shrink().minimalBody());
        assertThat(two.bodies).isEqualTo(one.bodies);
        assertThat(Files.readString(reproduceMarkdown(second)))
                .isEqualToIgnoringWhitespace(Files.readString(reproduceMarkdown(first))
                        .replace(first.toString(), second.toString()));
    }

    // --- artifact -------------------------------------------------------------

    @Test
    void theReproductionFolderTellsAnEngineerEverythingTheyNeed(@TempDir Path output) throws IOException {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        run(output, recorder, shrinking());

        Path directory = output.resolve("reproductions").resolve("createuser-body-.age-below_minimum");
        assertThat(directory).exists();

        String markdown = Files.readString(directory.resolve("reproduce.md"));
        assertThat(markdown)
                .contains("## 1. The case")
                .contains("## 3. What the document promised")
                .contains("## 4. What the service answered")
                .contains("## 5. Is it stable")
                .contains("## 6. Is it minimized")
                .contains("## 7. Run it again")
                .contains("only-cases")
                .contains(AGE_CASE)
                .doesNotContain("curl -");

        assertThat(Files.readString(directory.resolve("manifest.json")))
                .contains("specFingerprint")
                .contains("REPRODUCIBLE");
        assertThat(Files.readString(directory.resolve("request.json"))).contains("\"method\" : \"POST\"");
    }

    @Test
    void noSecretsReachTheReproductionFolder(@TempDir Path output) throws IOException {
        Recorder recorder = new Recorder(request -> ageIs(request, 17) ? 500 : 201);
        run(output, recorder, shrinking());

        Path directory = output.resolve("reproductions").resolve("createuser-body-.age-below_minimum");
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                assertThat(Files.readString(file)).doesNotContain("swordfish", "Bearer super-secret");
            }
        }
    }

    // --- helpers --------------------------------------------------------------

    private Path reproduceMarkdown(Path output) {
        return output.resolve("reproductions").resolve("createuser-body-.age-below_minimum")
                .resolve("reproduce.md");
    }

    private Options shrinking() {
        return options().allowUnsafeConfirmation(true).confirmationRuns(2).shrinkAttempts(30).build();
    }

    private boolean ageIs(PreparedRequest request, int age) {
        JsonNode body = body(request);
        return body.path("age").isNumber() && body.path("age").asInt() == age;
    }

    private JsonNode body(PreparedRequest request) {
        JsonNode parsed = parse(request.body());
        return parsed == null ? MAPPER.createObjectNode() : parsed;
    }

    private JsonNode parse(String body) {
        if (body == null) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private FuzzObservation finding(ApiFuzzReport report, String caseId) {
        return report.findings().stream()
                .filter(observation -> observation.fuzzCase().id().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No finding " + caseId + " in " + report.findings().stream()
                        .map(observation -> observation.fuzzCase().id()).toList()));
    }

    private static OptionsBuilder options() {
        return new OptionsBuilder();
    }

    private record Options(int confirmationRuns, boolean allowUnsafeConfirmation, int shrinkAttempts) {
    }

    private static final class OptionsBuilder {
        private int confirmationRuns;
        private boolean allowUnsafeConfirmation;
        private int shrinkAttempts;

        OptionsBuilder confirmationRuns(int runs) {
            this.confirmationRuns = runs;
            return this;
        }

        OptionsBuilder allowUnsafeConfirmation(boolean allow) {
            this.allowUnsafeConfirmation = allow;
            return this;
        }

        OptionsBuilder shrinkAttempts(int attempts) {
            this.shrinkAttempts = attempts;
            return this;
        }

        Options build() {
            return new Options(confirmationRuns, allowUnsafeConfirmation, shrinkAttempts);
        }
    }

    /** Records every body it was sent, so determinism can be asserted on the traffic itself. */
    private static final class Recorder implements ExchangeExecutor {

        private final Function<PreparedRequest, Integer> statuses;
        private final List<String> bodies = new ArrayList<>();

        Recorder(Function<PreparedRequest, Integer> statuses) {
            this.statuses = statuses;
        }

        @Override
        public RuntimeExchange execute(PreparedRequest request) {
            if (!"/users".equals(request.pathTemplate())) {
                return new RuntimeExchange(Map.of(), null, 200, "application/json", Map.of(),
                        "{\"id\":\"x\"}", 2L, null);
            }
            bodies.add(request.body());
            int status = statuses.apply(request);
            return new RuntimeExchange(Map.of(), null, status, "application/json", Map.of(),
                    status >= 500 ? "{\"message\":\"boom\"}" : "{}", 2L, null);
        }

        @Override
        public String baseUrl() {
            return "https://api.example.test";
        }
    }

    /** 500 on the first call to the target, 201 afterwards. */
    private static final class OnceThen implements Function<PreparedRequest, Integer> {
        private final int first;
        private final int rest;
        private boolean used;

        OnceThen(int first, int rest) {
            this.first = first;
            this.rest = rest;
        }

        @Override
        public Integer apply(PreparedRequest request) {
            if (!isTarget(request)) {
                return 201;
            }
            if (used) {
                return rest;
            }
            used = true;
            return first;
        }

        private boolean isTarget(PreparedRequest request) {
            return request.body() != null && request.body().contains("\"age\":17");
        }
    }

    /** Cycles through the given statuses for the target request. */
    private static final class Alternating implements Function<PreparedRequest, Integer> {
        private final int[] statuses;
        private int index;

        Alternating(int... statuses) {
            this.statuses = statuses;
        }

        @Override
        public Integer apply(PreparedRequest request) {
            if (request.body() == null || !request.body().contains("\"age\":17")) {
                return 201;
            }
            int status = statuses[Math.min(index, statuses.length - 1)];
            index++;
            return status;
        }
    }

    private ApiFuzzReport run(Path output, ExchangeExecutor executor, Options options) {
        ApiFuzzProperties properties = new ApiFuzzProperties(
                true, output.toString(), null, List.of("demo"), 20260101L, Set.of("POST"), true,
                List.of("/users"), null, null, 500, null,
                options.confirmationRuns(), options.allowUnsafeConfirmation(), options.shrinkAttempts(),
                List.of(), null, null);

        JsonBodyFactory bodyFactory = new JsonBodyFactory(MAPPER);
        ResponseClassifier classifier = new ResponseClassifier(new ResponseContractChecker(MAPPER), MAPPER);
        FindingConfirmer confirmer = new FindingConfirmer(executor, classifier, properties);
        RequestShrinker shrinker = new RequestShrinker(MAPPER, new ConstraintInventory(bodyFactory),
                bodyFactory, confirmer, properties);

        return new ApiFuzzRunner(
                new OpenApiSpecParser(),
                new OperationSelector(),
                new SafetyPolicy(properties.methods(), properties.allowUnsafeMethods(),
                        properties.includePaths(), properties.excludePaths()),
                new RequestPlanner(new RequestValueResolver(
                        new ApiExplorerProperties.ParameterProperties(Map.of(), Map.of()),
                        new ConstraintAwareValueFactory()), true),
                new FuzzCaseGenerator(),
                new BodyCaseGenerator(MAPPER, bodyFactory),
                bodyFactory,
                new JsonBodyMutator(MAPPER),
                new ConstraintInventory(bodyFactory),
                new BaselineSelfCheck(),
                confirmer,
                shrinker,
                new ReproductionWriter(MAPPER),
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
