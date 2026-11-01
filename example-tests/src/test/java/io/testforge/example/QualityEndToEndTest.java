package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.AccessMatrixRunner;
import io.testforge.api.fuzz.ApiFuzzRunner;
import io.testforge.api.fuzz.ProcessRunner;
import io.testforge.auth.AuthContext;
import io.testforge.contract.json.FieldType;
import io.testforge.contract.json.MessageContract;
import io.testforge.contract.monitor.ContractMonitorCase;
import io.testforge.contract.monitor.ContractMonitorRunner;
import io.testforge.contract.monitor.JsonPayloadContract;
import io.testforge.db.contract.DbContractRunner;
import io.testforge.db.contract.usage.DbSchemaUsage;
import io.testforge.http.ApiClient;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaMessageFilter;
import io.testforge.quality.QualityDimension;
import io.testforge.quality.QualityProperties;
import io.testforge.quality.QualityReport;
import io.testforge.quality.QualityRunner;
import io.testforge.quality.QualityStatus;
import io.testforge.security.SecurityRunner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * module-quality against the real producers, not hand-written JSON: every
 * module that feeds a dimension runs in one context with one run index,
 * each with a defect planted, and the assessment of that index must find
 * each defect in its dimension. A producer that renames a field it writes,
 * or registers its report after the index is closed, breaks this test.
 */
class QualityEndToEndTest {

    final Path run = Path.of("build/quality-e2e", UUID.randomUUID().toString());
    final WireMockServer server =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    @BeforeEach
    void product() {
        server.start();
        server.stubFor(get(anyUrl()).withHeader("Authorization", absent()).atPriority(1)
                .willReturn(aResponse().withStatus(401)));
        server.stubFor(get(anyUrl()).atPriority(5).willReturn(okJson("{}")));
        // contracts: the service dropped "status", which the document requires
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks")).atPriority(2)
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\"}]}")));
        // authorization: admins are let in; members get in too, which the matrix says they must not
        // reliability: a status endpoint the suite calls answers 503
        server.stubFor(get(urlPathEqualTo("/api/v1/status/42")).atPriority(2).willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo("/api/v1/admin/audit"))
                .withHeader("Authorization", equalTo("Bearer admin-token")).atPriority(2)
                .willReturn(okJson("{}")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void everyProducerReachesItsDimension() throws Exception {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        io.testforge.core.TestForgeCoreAutoConfiguration.class,
                        io.testforge.reporting.TestForgeReportingAutoConfiguration.class,
                        io.testforge.http.TestForgeHttpAutoConfiguration.class,
                        io.testforge.auth.TestForgeAuthAutoConfiguration.class,
                        io.testforge.api.discovery.TestForgeApiDiscoveryAutoConfiguration.class,
                        io.testforge.api.explorer.TestForgeApiExplorerAutoConfiguration.class,
                        io.testforge.api.explorer.TestForgeContractGuardAutoConfiguration.class,
                        io.testforge.api.explorer.TestForgeAccessMatrixAutoConfiguration.class,
                        io.testforge.api.fuzz.TestForgeApiFuzzAutoConfiguration.class,
                        io.testforge.db.TestForgeDbAutoConfiguration.class,
                        io.testforge.db.contract.TestForgeDbContractAutoConfiguration.class,
                        io.testforge.kafka.TestForgeKafkaAutoConfiguration.class,
                        io.testforge.contract.TestForgeContractAutoConfiguration.class,
                        io.testforge.contract.monitor.TestForgeContractMonitorAutoConfiguration.class,
                        io.testforge.security.TestForgeSecurityAutoConfiguration.class))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(ProcessRunner.class, ApiFuzzExampleTest.StubProcessRunner::new)
                .withBean(DbSchemaUsage.class, () -> DbSchemaUsage.ofMappings(List.of()))
                .withBean(ContractMonitorCase.class, () -> new ContractMonitorCase("partner-status",
                        KafkaMessageFilter.builder().topic("partner.events").build(),
                        JsonPayloadContract.of(MessageContract.named("partner-status")
                                .required("$.payload.status", FieldType.STRING).build())))
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "forge.reporting.artifacts.enabled=true",
                        "forge.reporting.artifacts.dir=" + run.resolve("artifacts"),
                        "forge.reporting.artifacts.run-id=e2e",
                        "forge.http.base-url=http://localhost:" + server.port(),
                        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml",
                        "forge.api-discovery.specs.access.location=classpath:/openapi/access-demo.yaml",
                        "forge.contract-guard.enabled=true",
                        "forge.contract-guard.specs=demo",
                        "forge.api-explorer.parameters.defaults.taskId=t-1",
                        "forge.auth.enabled=true",
                        "forge.auth.roles.admin.token=admin-token",
                        "forge.auth.roles.member.token=member-token",
                        "forge.access-matrix.enabled=true",
                        "forge.access-matrix.specs=access",
                        "forge.access-matrix.output-dir=" + run.resolve("access-matrix"),
                        "forge.access-matrix.expect.admin=allow",
                        "forge.access-matrix.expect.member=allow",
                        "forge.access-matrix.expect.anonymous=401",
                        "forge.access-matrix.operations.readAuditLog.member=deny",
                        "forge.api-fuzz.enabled=true",
                        "forge.api-fuzz.output-dir=" + run.resolve("api-fuzz"),
                        "forge.api-fuzz.base-url=http://localhost:" + server.port(),
                        "forge.api-fuzz.specs=demo",
                        "forge.db-contract.enabled=true",
                        "forge.db-contract.schema=PUBLIC",
                        "forge.db-contract.include-tables=e2e_orders.*",
                        "forge.db-contract.output-dir=" + run.resolve("db-contract"),
                        "forge.db-contract.baseline-file=" + run.resolve("db-contract/baseline.json"),
                        "forge.contract-monitor.enabled=true",
                        "forge.contract-monitor.output-dir=" + run.resolve("contract-monitor"),
                        "forge.contract-monitor.baseline-dir=" + run.resolve("contract-monitor-baseline"),
                        "forge.security.passive.enabled=true",
                        "forge.security.output-dir=" + run.resolve("security"))
                .run(context -> {
                    // data: a column a consumer selects is dropped after the baseline
                    JdbcTemplate jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
                    jdbc.execute("CREATE TABLE e2e_orders (id BIGINT PRIMARY KEY, amount NUMERIC(10,2))");
                    context.getBean(DbContractRunner.class).writeBaseline();
                    jdbc.execute("ALTER TABLE e2e_orders DROP COLUMN amount");
                    context.getBean(DbContractRunner.class).run();
                    // contracts: a test calls the drifted operation; the guard records it
                    AuthContext.callAs("admin", () -> context.getBean(ApiClient.class).request().get("/api/v1/tasks"));
                    // the test expects the 503 and passes; the service still failed
                    AuthContext.callAs("admin",
                            () -> context.getBean(ApiClient.class).request().get("/api/v1/status/42"));
                    // events: the payload's status is a number, the contract says string
                    context.getBean(KafkaMessageBuffer.class).append(new KafkaMessage("partner.events", 0, 1, "k",
                            "{\"payload\":{\"status\":7}}", Instant.EPOCH, Map.of()));
                    context.getBean(ContractMonitorRunner.class).run();
                    context.getBean(AccessMatrixRunner.class).run();
                    context.getBean(ApiFuzzRunner.class).run();
                    context.getBean(SecurityRunner.class).run();
                });

        QualityReport report = new QualityRunner(new QualityProperties(true, List.of(run.resolve("none").toString()),
                run.resolve("artifacts").toString(), null, run.resolve("quality").toString(), null, null, null, null,
                null), new ObjectMapper(), null).run();

        assertThat(report.runId()).isEqualTo("e2e");
        assertThat(report.dimension(QualityDimension.CONTRACTS).orElseThrow().issues())
                .anyMatch(issue -> issue.startsWith("api ") && issue.contains("/api/v1/tasks"))
                .contains("event partner-status");
        assertThat(report.dimension(QualityDimension.DATA).orElseThrow().issues())
                .contains("BREAKING COLUMN_REMOVED E2E_ORDERS.AMOUNT");
        assertThat(report.dimension(QualityDimension.AUTHORIZATION).orElseThrow().issues())
                .contains("GET /api/v1/admin/audit as member");
        assertThat(report.dimension(QualityDimension.ROBUSTNESS).orElseThrow().status())
                .isEqualTo(QualityStatus.PASS);
        assertThat(report.dimension(QualityDimension.SECURITY).orElseThrow().facts().getFirst())
                .startsWith("new findings:");
        assertThat(report.dimension(QualityDimension.RELIABILITY).orElseThrow().issues())
                .contains("GET /api/v1/status/{n}");
        assertThat(report.dimension(QualityDimension.API_COVERAGE).orElseThrow().issues())
                .as("the suite never creates a task").contains("demo POST /api/v1/tasks");
        assertThat(Files.readString(Path.of(report.reportMarkdown()))).contains("- run: e2e");
    }
}
