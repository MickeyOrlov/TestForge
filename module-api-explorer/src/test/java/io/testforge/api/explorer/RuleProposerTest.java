package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.http.Redactor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleProposerTest {

    private static ApiExplorerProperties properties(Map<String, Map<String, String>> configured,
                                                    List<ExtractionRule> rules) {
        return new ApiExplorerProperties(true, null, null, List.of(), null, null, null, null, null, null,
                new ApiExplorerProperties.ParameterProperties(Map.of(), configured), null, rules, null);
    }

    private static List<ExplorableOperation> operations(String yaml) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        OpenAPI openApi = new OpenAPIV3Parser().readContents(yaml, null, options).getOpenAPI();
        return new OperationSelector().select("inline", openApi);
    }

    @Test
    void theParentCollectionFeedsItsItems() {
        List<ProposedRule> proposals = RuleProposer.propose(ExplorerFixtures.operations(), properties(Map.of(), List.of()));

        assertThat(proposals).filteredOn(proposal -> proposal.into().equals("getTask.taskId"))
                .singleElement()
                .satisfies(proposal -> {
                    assertThat(proposal.from()).isEqualTo("listTasks");
                    assertThat(proposal.jsonPath()).isEqualTo("$.items[0].id");
                    assertThat(proposal.reason()).isEqualTo("GET /tasks lists the items GET /tasks/{taskId} reads");
                });
    }

    @Test
    void configuredOrAlreadyFedParametersGetNoProposal() {
        assertThat(RuleProposer.propose(ExplorerFixtures.operations(),
                properties(Map.of("getTask", Map.of("taskId", "t-1")), List.of())))
                .noneMatch(proposal -> proposal.into().equals("getTask.taskId"));
        assertThat(RuleProposer.propose(ExplorerFixtures.operations(),
                properties(Map.of(), List.of(new ExtractionRule("listTasks", "$.x", List.of("taskId"))))))
                .noneMatch(proposal -> proposal.into().endsWith(".taskId"));
    }

    @Test
    void aMatchingPropertyNameLinksUnrelatedPaths() {
        List<ExplorableOperation> operations = operations("""
                openapi: 3.0.3
                info: {title: t, version: '1'}
                paths:
                  /me:
                    get:
                      operationId: currentUser
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  profile:
                                    type: object
                                    properties:
                                      tenantId: {type: string}
                  /tenants/{tenantId}/users:
                    get:
                      operationId: listTenantUsers
                      parameters:
                        - {name: tenantId, in: path, required: true, schema: {type: string}}
                      responses:
                        '200': {description: OK}
                  /orgs/{orgId}:
                    get:
                      operationId: getOrg
                      parameters:
                        - {name: orgId, in: path, required: true, schema: {type: string}}
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  tenantId: {type: string}
                """);

        assertThat(RuleProposer.propose(operations, properties(Map.of(), List.of())))
                .as("getOrg needs a path parameter itself, so it cannot be a producer")
                .singleElement()
                .satisfies(proposal -> {
                    assertThat(proposal.from()).isEqualTo("currentUser");
                    assertThat(proposal.jsonPath()).isEqualTo("$.profile.tenantId");
                    assertThat(proposal.into()).isEqualTo("listTenantUsers.tenantId");
                    assertThat(proposal.reason()).contains("returns a property named tenantId");
                });
    }

    @Test
    void theShortestPathWinsAndObjectsAreNeverProposed() {
        List<ExplorableOperation> operations = operations("""
                openapi: 3.0.3
                info: {title: t, version: '1'}
                paths:
                  /items:
                    get:
                      operationId: listItems
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                type: object
                                properties:
                                  meta:
                                    type: object
                                    properties:
                                      owner:
                                        type: object
                                        properties:
                                          id: {type: string}
                                  data:
                                    type: array
                                    items:
                                      type: object
                                      properties:
                                        id: {type: string}
                  /items/{itemId}:
                    get:
                      operationId: getItem
                      parameters:
                        - {name: itemId, in: path, required: true, schema: {type: string}}
                      responses:
                        '200': {description: OK}
                """);

        assertThat(RuleProposer.propose(operations, properties(Map.of(), List.of())))
                .singleElement()
                .extracting(ProposedRule::jsonPath)
                .isEqualTo("$.data[0].id");
    }

    @Test
    void proposalsLandInTheReportAndAreNeverApplied(@TempDir Path output) throws Exception {
        ApiExplorerProperties properties = new ApiExplorerProperties(true, output.toString(), null, List.of(),
                null, null, null, null, null, null, null, null, null, null);
        ExchangeExecutor server = new ExchangeExecutor() {
            @Override
            public RuntimeExchange execute(PreparedRequest request) {
                return new RuntimeExchange(Map.of(), null, request.pathTemplate().equals("/tasks") ? 200 : 404,
                        "application/json", Map.of(), "{\"items\":[{\"id\":\"real\",\"title\":\"t\"}]}", 1L, null);
            }

            @Override
            public String baseUrl() {
                return "http://localhost:0";
            }
        };

        ApiExplorerReport report = new ApiExplorerRunner(new OpenApiSpecParser(), new OperationSelector(),
                new SafetyPolicy(properties),
                new RequestPlanner(new RequestValueResolver(properties.parameters(), new SchemaValueFactory())),
                server, new ResponseContractChecker(new ObjectMapper()),
                new ObservationFactory(new Redactor(new ObjectMapper(), List.of(), List.of()), 4000),
                new ObjectMapper(),
                new ApiDiscoveryProperties(true, null, null, null, null,
                        Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION))),
                properties).run();

        SpecExplorationReport spec = report.specs().getFirst();
        assertThat(spec.proposals()).extracting(ProposedRule::into).contains("getTask.taskId");
        assertThat(spec.observations()).filteredOn(o -> o.key().equals("GET /tasks/{taskId}"))
                .singleElement().extracting(ObservationSummary::status).isEqualTo(404);

        String markdown = Files.readString(Path.of(report.reportMarkdown()));
        assertThat(markdown).contains("### Proposed extraction rules")
                .contains("      - from: listTasks\n        json-path: \"$.items[0].id\"\n        into: [getTask.taskId]");
    }
}
