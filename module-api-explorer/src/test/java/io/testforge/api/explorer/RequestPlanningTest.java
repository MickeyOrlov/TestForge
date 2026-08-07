package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Value resolution and request building — where "the document says it needs an
 * id" turns into an actual URL, or into a reason it could not.
 */
class RequestPlanningTest {

    private RequestPlanner planner(Map<String, String> configured) {
        return new RequestPlanner(new RequestValueResolver(
                new ApiExplorerProperties.ParameterProperties(configured, Map.of()),
                new SchemaValueFactory()));
    }

    @Test
    void configuredValuesWinOverEverythingTheDocumentOffers() {
        PlannedRequest plan = planner(Map.of("from", "2030-12-31"))
                .plan(ExplorerFixtures.operation("listReports"));

        assertThat(plan.sendable()).isTrue();
        assertThat(plan.bindings())
                .filteredOn(binding -> binding.name().equals("from"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.source()).isEqualTo(ValueSource.CONFIGURED);
                    assertThat(binding.value()).isEqualTo("2030-12-31");
                });
    }

    @Test
    void documentExamplesBeatGeneratedValues() {
        PlannedRequest plan = planner(Map.of()).plan(ExplorerFixtures.operation("listReports"));

        assertThat(plan.bindings())
                .filteredOn(binding -> binding.name().equals("from"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.source()).isEqualTo(ValueSource.EXAMPLE);
                    assertThat(binding.value()).isEqualTo("2024-03-01");
                });
    }

    @Test
    void enumsAreUsedWhenNoExampleOrDefaultExists() {
        PlannedRequest plan = planner(Map.of()).plan(ExplorerFixtures.operation("listReports"));

        assertThat(plan.bindings())
                .filteredOn(binding -> binding.name().equals("format"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.source()).isEqualTo(ValueSource.ENUM);
                    assertThat(binding.value()).isEqualTo("json");
                });
    }

    @Test
    void generatedValuesFollowTheDeclaredFormatAndAreDeterministic() {
        PlannedRequest first = planner(Map.of()).plan(ExplorerFixtures.operation("getTask"));
        PlannedRequest second = planner(Map.of()).plan(ExplorerFixtures.operation("getTask"));

        assertThat(first.bindings()).singleElement().satisfies(binding -> {
            assertThat(binding.source()).isEqualTo(ValueSource.GENERATED);
            assertThat(binding.value()).isEqualTo("testforge");
        });
        assertThat(first.request().resolvedTarget()).isEqualTo(second.request().resolvedTarget());
    }

    @Test
    void optionalQueryParametersAreLeftOutUnlessConfigured() {
        PlannedRequest generated = planner(Map.of()).plan(ExplorerFixtures.operation("listTasks"));
        assertThat(generated.request().queryParameters()).isEmpty();

        PlannedRequest configured = planner(Map.of("limit", "5"))
                .plan(ExplorerFixtures.operation("listTasks"));
        assertThat(configured.request().queryParameters()).containsEntry("limit", "5");
    }

    @Test
    void operationsRequiringARequestBodyAreSkippedNotInvented() {
        PlannedRequest plan = planner(Map.of()).plan(ExplorerFixtures.operation("createTask"));

        assertThat(plan.sendable()).isFalse();
        assertThat(plan.skipReason()).isEqualTo(SkipReason.REQUEST_BODY_REQUIRED);
    }

    @Test
    void queryStringOrderIsStableRegardlessOfDeclarationOrder() {
        PreparedRequest request = new PreparedRequest("GET", "/reports", Map.of(),
                new java.util.LinkedHashMap<>(Map.of("z", "1", "a", "2")));

        assertThat(request.resolvedTarget()).isEqualTo("/reports?a=2&z=1");
    }

    @Test
    void pathPlaceholdersAreSubstituted() {
        PreparedRequest request = new PreparedRequest("GET", "/tasks/{taskId}",
                Map.of("taskId", "task-1"), Map.of());

        assertThat(request.resolvedPath()).isEqualTo("/tasks/task-1");
    }
}
