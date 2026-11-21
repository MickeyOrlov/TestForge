package io.testforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;

/** The delegated testssl.sh assessment, replayed from a real testssl.sh 3.2.5 report. */
class SecurityTestsslTest {
    private final ObjectMapper mapper = new ObjectMapper();

    // testssl.sh 3.2.5 (pinned image) against an owned local server with a self-signed certificate
    private JsonNode selfSigned() throws IOException {
        try (InputStream in =
                getClass().getResourceAsStream("/testssl/testssl-3.2.5-self-signed.json")) {
            return mapper.readTree(in);
        }
    }

    private static final class Replay implements TestsslEngine {
        final TestsslEngine.Result result;
        final List<String> targets = new ArrayList<>();
        final AtomicReference<Path> directory = new AtomicReference<>();

        Replay(TestsslEngine.Result result) {
            this.result = result;
        }

        @Override
        public Result execute(Path dir, String hostPort, SecurityTransportProperties.Testssl p) {
            targets.add(hostPort);
            directory.set(dir);
            return result;
        }
    }

    private static SecurityTransportProperties properties(boolean testssl, boolean testEnvironment) {
        return new SecurityTransportProperties(
                true,
                List.of("127.0.0.1", "tls.testforge.test:8443"),
                30,
                Duration.ofMillis(500),
                false,
                false,
                new SecurityTransportProperties.Testssl(
                        testssl, testEnvironment, null, null, List.of("tls.testforge.test")));
    }

    private static SecurityCollector check(SecurityTransportProperties p, TestsslEngine engine)
            throws Exception {
        var collector = new SecurityCollector(1000);
        // nothing listens on port 9: the JDK part is not_measured, testssl.sh still runs
        new SecurityTransportRunner(p, collector, SSLContext.getDefault(), Clock.systemUTC(), engine)
                .check("https://127.0.0.1:9");
        return collector;
    }

    @Test
    void testsslFindingsKeepTheirSeverityAndOnlyIdentifiersEnterTheReport() throws Exception {
        var engine = new Replay(new TestsslEngine.Result(selfSigned(), TestsslEngine.Phase.READY, 0));
        var collector = check(properties(true, true), engine);

        assertThat(engine.targets).containsExactly("127.0.0.1:9");
        assertThat(collector.findings())
                .filteredOn(f -> f.check().startsWith("testssl-"))
                .extracting(f -> f.check() + " " + f.severity())
                .containsExactlyInAnyOrder(
                        "testssl-cert_chain_of_trust CRITICAL",
                        "testssl-cert_expirationStatus HIGH",
                        "testssl-cert_notAfter HIGH",
                        "testssl-cert_revocation HIGH",
                        "testssl-cipherlist_OBSOLETED LOW",
                        "testssl-LUCKY13 LOW");
        assertThat(collector.findings())
                .filteredOn(f -> f.check().equals("testssl-LUCKY13"))
                .extracting(SecurityFinding::evidence)
                .containsExactly("testssl.sh rated LUCKY13 low (CVE-2013-0169, CWE-310)");
        assertThat(collector.findings().toString())
                .as("testssl.sh's finding text holds observed values")
                .doesNotContain("self signed", "2026-", "192.168", "tls.testforge.test", "TLS_");
        assertThat(collector.coverage())
                .filteredOn(c -> c.status().equals("measured"))
                .extracting(SecurityCoverage::check)
                .contains("tls-testssl", "testssl-SSLv2", "testssl-SSLv3", "testssl-TLS1",
                        "testssl-TLS1_1", "testssl-heartbleed", "testssl-ROBOT")
                .doesNotContain("testssl-engine_problem", "testssl-scanTime");
        assertThat(engine.directory.get()).as("the private directory is removed").doesNotExist();
    }

    @Test
    void anIncompleteScanAddsNoFindingAndKeepsTheGateClosed() throws Exception {
        JsonNode unreachable =
                mapper.readTree(
                        "[{\"id\":\"engine_problem\",\"severity\":\"WARN\",\"finding\":\"x\"},"
                                + "{\"id\":\"scanProblem\",\"severity\":\"FATAL\",\"finding\":\"x\"}]");
        JsonNode unknown = mapper.readTree("[{\"id\":\"SSLv3\",\"severity\":\"SEVERE\"}]");
        for (var result :
                List.of(
                        new TestsslEngine.Result(unreachable, TestsslEngine.Phase.EXECUTION, 246),
                        new TestsslEngine.Result(selfSigned(), TestsslEngine.Phase.EXECUTION, 1),
                        new TestsslEngine.Result(unknown, TestsslEngine.Phase.READY, 0),
                        new TestsslEngine.Result(null, TestsslEngine.Phase.TIMEOUT, -1),
                        new TestsslEngine.Result(null, TestsslEngine.Phase.CLEANUP, 1))) {
            var collector = check(properties(true, true), new Replay(result));
            assertThat(collector.findings()).noneMatch(f -> f.check().startsWith("testssl-"));
            assertThat(collector.coverage())
                    .anyMatch(
                            c -> c.check().equals("tls-testssl")
                                    && c.status().equals("not_measured")
                                    && c.reason().contains("phase " + result.phase()));
        }
    }

    @Test
    void testsslIsOffByDefaultAndNeedsAnExplicitTestEnvironment() throws Exception {
        var never = new Replay(null);
        check(properties(false, false), never);
        assertThat(never.targets).isEmpty();
        assertThat(new SecurityTransportProperties(true, List.of("a"), 30, null, false, false)
                        .testssl()
                        .enabled())
                .isFalse();

        assertThatThrownBy(() -> check(properties(true, false), never))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("test-environment");
        assertThat(never.targets).isEmpty();
    }

    @Test
    void imageAliasesAndBudgetAreValidated() {
        assertThatThrownBy(() -> new SecurityTransportProperties.Testssl(
                        true, true, "ghcr.io/testssl/testssl.sh:3.2", null, null))
                .hasMessageContaining("pinned by digest");
        assertThatThrownBy(() -> new SecurityTransportProperties.Testssl(
                        true, true, null, Duration.ofHours(1), null))
                .hasMessageContaining("1s..30m");
        assertThatThrownBy(() -> new SecurityTransportProperties(true, List.of("127.0.0.1"), 30,
                        null, false, false,
                        new SecurityTransportProperties.Testssl(true, true, null, null,
                                List.of("elsewhere.example.test"))))
                .hasMessageContaining("must be allowed hosts");
    }

    @Test
    void theNestedBlockBindsAndTheEngineIsReplaceable() throws Exception {
        var engine = new Replay(new TestsslEngine.Result(selfSigned(), TestsslEngine.Phase.READY, 0));
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
                .withBean(TestsslEngine.class, () -> engine)
                .withPropertyValues(
                        "forge.security.transport.enabled=true",
                        "forge.security.transport.allowed-hosts[0]=127.0.0.1",
                        "forge.security.transport.timeout=500ms",
                        "forge.security.transport.testssl.enabled=true",
                        "forge.security.transport.testssl.test-environment=true",
                        "forge.security.transport.testssl.time-budget=5m",
                        "forge.security.output-dir=" + Files.createTempDirectory("testssl-report"))
                .run(c -> {
                    assertThat(c.getBean(SecurityTransportProperties.class).testssl().timeBudget())
                            .isEqualTo(Duration.ofMinutes(5));
                    c.getBean(SecurityTransportRunner.class).check("https://127.0.0.1:9");
                    assertThat(engine.targets).containsExactly("127.0.0.1:9");
                });
    }
}
