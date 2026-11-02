package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.security.SecurityCollector;
import io.testforge.security.SecurityProperties;
import io.testforge.security.SecurityRunner;
import io.testforge.security.Severity;
import io.testforge.security.audit.SecurityAuditImports;
import io.testforge.security.audit.SecurityAuditPlanner;
import io.testforge.security.audit.SecurityAuditProfiles;
import io.testforge.security.audit.SecurityAuditRunner;
import io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class SecurityAuditResultsExampleTest {
    @Test
    void existingSecurityFindingsAndBaselineFeedTheAuditWithoutAnotherBaseline(
            @TempDir Path temporary) throws Exception {
        Path dir = temporary.toRealPath();
        var collector = new SecurityCollector(100);
        collector.covered("missing-auth", "GET /protected", "owner", "measured", null);
        collector.finding(
                "missing-auth",
                Severity.HIGH,
                "GET /protected",
                "credential that should be rejected was accepted",
                "API2:2023");
        var props =
                new SecurityProperties(
                        null,
                        dir.resolve("upstream").toString(),
                        dir.resolve("baseline.json").toString(),
                        null,
                        null,
                        null);
        var upstream = new SecurityRunner(props, collector, ArtifactSink.NO_OP, () -> true);
        assertThat(upstream.run().healthy()).isFalse();
        var profile =
                SecurityAuditProfiles.parse(
                        """
{"id":"internal","environment":"TEST","allowedHosts":["api.internal"],
"assets":[{"id":"api","layer":"APPLICATION","kind":"URL","location":"https://api.internal"}],
"engines":[{"engine":"TESTFORGE","enabled":true,"version":"1.3.0-SNAPSHOT"}]}
""");
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TestForgeSecurityAuditAutoConfiguration.class))
                .withPropertyValues(
                        "forge.security-audit.enabled=true",
                        "forge.security-audit.results.output-dir=" + dir.resolve("audit"))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var imports = c.getBean(SecurityAuditImports.class);
                            var runner = c.getBean(SecurityAuditRunner.class);
                            var plan = c.getBean(SecurityAuditPlanner.class).plan(profile);
                            try {
                                var first =
                                        imports.securityReport(
                                                "api",
                                                "1.3.0-SNAPSHOT",
                                                Files.readAllBytes(
                                                        dir.resolve(
                                                                "upstream/security-findings.json")),
                                                Map.of("GET /protected", "protected-resource"));
                                var report = runner.run(plan, List.of(first));
                                assertThat(report.complete()).isTrue();
                                assertThat(report.healthy()).isFalse();
                                upstream.writeBaseline(); // Explicit existing API; audit never
                                // promotes it.
                                byte[] baseline = Files.readAllBytes(dir.resolve("baseline.json"));
                                assertThat(upstream.run().healthy()).isTrue();
                                var accepted =
                                        imports.securityReport(
                                                "api",
                                                "1.3.0-SNAPSHOT",
                                                Files.readAllBytes(
                                                        dir.resolve(
                                                                "upstream/security-findings.json")),
                                                Map.of("GET /protected", "protected-resource"));
                                assertThat(runner.run(plan, List.of(accepted)).healthy()).isTrue();
                                assertThat(Files.readAllBytes(dir.resolve("baseline.json")))
                                        .isEqualTo(baseline);
                                assertThat(runner.run(plan, List.of()).healthy()).isFalse();
                            } catch (java.io.IOException e) {
                                throw new AssertionError(e);
                            }
                        });
    }
}
