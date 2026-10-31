package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.auth.AuthContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccessMatrixTest {

    @TempDir
    Path outputDir;

    /** Stands in for the API: answers by role and path, and remembers who called. */
    private static final class RoleAwareExecutor implements ExchangeExecutor {

        final List<String> calls = new CopyOnWriteArrayList<>();
        private final BiFunction<String, String, Integer> status;

        RoleAwareExecutor(BiFunction<String, String, Integer> status) {
            this.status = status;
        }

        @Override
        public RuntimeExchange execute(PreparedRequest request) {
            String role = AuthContext.anonymous() ? "anonymous" : AuthContext.currentRole().orElse("none");
            calls.add(role + " " + request.method() + " " + request.resolvedPath());
            int answer = status.apply(role, request.resolvedPath());
            return answer < 0
                    ? RuntimeExchange.failed(Map.of(), "connection refused", 1)
                    : new RuntimeExchange(Map.of(), null, answer, null, Map.of(), null, 1, null);
        }

        @Override
        public String baseUrl() {
            return "http://api.test";
        }
    }

    /** admin may do everything, user may only list, anonymous gets 401. */
    private static int product(String role, String path) {
        return switch (role) {
            case "admin" -> 200;
            case "user" -> path.equals("/tasks") ? 200 : 403;
            default -> 401;
        };
    }

    private AccessMatrixRunner runner(AccessMatrixProperties properties, ExchangeExecutor executor,
                                      Set<String> knownRoles, RecordingArtifactSink sink) {
        ApiExplorerProperties explorer = ExplorerFixtures.properties(Map.of(
                "parameterDefaults", Map.of("taskId", "t-1")));
        ApiDiscoveryProperties discovery = new ApiDiscoveryProperties(true, null, null, null, null,
                Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION)));
        return new AccessMatrixRunner(new OpenApiSpecParser(), discovery, explorer, properties, executor,
                knownRoles, new ObjectMapper(), sink);
    }

    private AccessMatrixProperties matrix(Map<String, String> expect, Map<String, Map<String, String>> operations) {
        return new AccessMatrixProperties(true, outputDir.toString(), null, null, expect, operations, null);
    }

    private static AccessCell cell(AccessMatrixReport report, String operationId, String role) {
        return report.rows().stream().filter(row -> row.operationId().equals(operationId)).findFirst().orElseThrow()
                .cells().stream().filter(cell -> cell.role().equals(role)).findFirst().orElseThrow();
    }

    @Test
    void everyReadOperationIsCalledOncePerRoleAndHeldToItsRule() {
        var executor = new RoleAwareExecutor(AccessMatrixTest::product);
        var report = runner(matrix(Map.of("admin", "allow", "user", "deny", "anonymous", "401"),
                Map.of("listTasks", Map.of("user", "allow"))), executor, null, new RecordingArtifactSink()).run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.roles()).containsExactly("admin", "anonymous", "user");
        assertThat(cell(report, "getTask", "user").verdict()).isEqualTo(AccessCell.Verdict.OK);
        assertThat(cell(report, "listTasks", "user").status()).isEqualTo(200);
        assertThat(executor.calls).contains("anonymous GET /tasks/t-1", "user GET /tasks/t-1", "admin GET /tasks/t-1");
    }

    @Test
    void aRoleThatGetsInWhereItShouldNotIsAViolation() {
        // the product forgot to check the role on getTask
        var executor = new RoleAwareExecutor((role, path) -> role.equals("anonymous") ? 401 : 200);
        var runner = runner(matrix(Map.of("admin", "allow", "user", "deny", "anonymous", "deny"),
                Map.of("listTasks", Map.of("user", "allow"))), executor, null, new RecordingArtifactSink());

        assertThatThrownBy(runner::assertHealthy)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("GET /tasks/{taskId} (getTask) as user: got 200, expected deny")
                .hasMessageContaining("access-matrix.md");
    }

    @Test
    void aRoleShutOutOfWhatItShouldReachIsAViolationToo() {
        var report = runner(matrix(Map.of("admin", "allow", "user", "allow", "anonymous", "deny"), Map.of()),
                new RoleAwareExecutor(AccessMatrixTest::product), null, new RecordingArtifactSink()).run();

        assertThat(report.healthy()).isFalse();
        assertThat(cell(report, "getTask", "user").verdict()).isEqualTo(AccessCell.Verdict.VIOLATION);
    }

    @Test
    void writeMethodsAreSkippedUnderTheExplorersSafetyPolicy() {
        var executor = new RoleAwareExecutor(AccessMatrixTest::product);
        var report = runner(matrix(Map.of("admin", "allow"), Map.of()), executor, null,
                new RecordingArtifactSink()).run();

        assertThat(cell(report, "deleteTask", "admin").verdict()).isEqualTo(AccessCell.Verdict.SKIPPED);
        assertThat(cell(report, "deleteTask", "admin").detail()).contains("not enabled");
        assertThat(executor.calls).noneMatch(call -> call.contains("DELETE") || call.contains("POST"));
    }

    @Test
    void aServerErrorOrAFailedCallIsAnErrorNotAPass() {
        var executor = new RoleAwareExecutor((role, path) -> role.equals("admin") ? 500 : -1);
        var report = runner(matrix(Map.of("admin", "allow", "user", "deny"), Map.of()), executor, null,
                new RecordingArtifactSink()).run();

        assertThat(report.healthy()).isFalse();
        assertThat(cell(report, "listTasks", "admin").verdict()).isEqualTo(AccessCell.Verdict.ERROR);
        assertThat(cell(report, "listTasks", "user").detail()).isEqualTo("connection refused");
        assertThat(report.violations()).isZero();
    }

    @Test
    void undeclaredCellsAreReportedWithAProposalAndFailOnlyWhenAskedTo() throws Exception {
        var executor = new RoleAwareExecutor(AccessMatrixTest::product);
        var report = runner(new AccessMatrixProperties(true, outputDir.toString(), null, null, null, null, null),
                executor, Set.of("admin", "user"), new RecordingArtifactSink()).run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.roles()).containsExactly("admin", "user", "anonymous");
        assertThat(report.undeclared()).isPositive();
        String markdown = Files.readString(Path.of(report.reportMarkdown()));
        assertThat(markdown)
                .contains("## Proposed expectations")
                .contains("      admin: allow\n")
                .contains("      anonymous: deny\n")
                .contains("      user: deny\n")
                .contains("      listTasks:\n        user: allow\n");

        var strict = new AccessMatrixProperties(true, outputDir.toString(), null, null, null, null, true);
        assertThat(runner(strict, executor, Set.of("admin", "user"), new RecordingArtifactSink()).run().healthy())
                .isFalse();
    }

    @Test
    void aRoleModuleAuthDoesNotKnowFailsBeforeAnyRequest() {
        var executor = new RoleAwareExecutor(AccessMatrixTest::product);
        var runner = runner(matrix(Map.of("admn", "allow"), Map.of()), executor, Set.of("admin", "user"),
                new RecordingArtifactSink());

        assertThatThrownBy(runner::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("[admn]")
                .hasMessageContaining("[admin, user]");
        assertThat(executor.calls).isEmpty();
    }

    @Test
    void theReportIsWrittenAndRegisteredInTheRunIndex() {
        var sink = new RecordingArtifactSink();
        var report = runner(matrix(Map.of("admin", "allow"), Map.of()),
                new RoleAwareExecutor(AccessMatrixTest::product), null, sink).run();

        assertThat(Path.of(report.reportJson())).exists();
        assertThat(sink.registered).extracting(artifact -> artifact.name())
                .containsExactly("access-matrix.json", "access-matrix.md");
        assertThat(sink.registered.get(0).metadata()).containsEntry("violations", "0");
    }

    @Test
    void badRulesFailTheConfigurationAllAtOnce() {
        assertThatThrownBy(() -> matrix(Map.of("admin", "alow"), Map.of("getTask", Map.of("user", "6xx"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expect.admin")
                .hasMessageContaining("operations.getTask.user");
    }

    @Test
    void rulesReadAsWritten() {
        assertThat(AccessRule.parse("allow").matches(404)).isTrue();
        assertThat(AccessRule.parse("allow").matches(403)).isFalse();
        assertThat(AccessRule.parse("allow").matches(500)).isFalse();
        assertThat(AccessRule.parse("deny").matches(401)).isTrue();
        assertThat(AccessRule.parse("deny").matches(404)).isFalse();
        assertThat(AccessRule.parse("401, 403,404").matches(404)).isTrue();
        assertThat(AccessRule.parse("2xx").matches(204)).isTrue();
        assertThat(AccessRule.parse("2xx").matches(302)).isFalse();
    }
}
