package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.security.audit.SecurityAuditAdapter;
import io.testforge.security.audit.SecurityAuditCommand;
import io.testforge.security.audit.SecurityAuditExecution;
import io.testforge.security.audit.SecurityAuditExecutionContext;
import io.testforge.security.audit.SecurityAuditExecutor;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditProfiles;
import io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

class SecurityAuditExecutionExampleTest {
    @Test
    void explicitTrustedAdapterProducesAProcessReceiptAndReusesTheExistingAssessment(
            @TempDir Path temporary) throws Exception {
        Path dir = temporary.toRealPath();
        var profile =
                SecurityAuditProfiles.parse(
                        """
{"id":"internal","environment":"LOCAL","allowedHosts":["127.0.0.1"],
 "assets":[{"id":"api","layer":"APPLICATION","kind":"URL","location":"http://127.0.0.1"}],
 "engines":[{"engine":"TESTFORGE","enabled":true,"version":"1.0.0"}]}
""");
        SecurityAuditAdapter fixture =
                new SecurityAuditAdapter() {
                    public Engine engine() {
                        return Engine.TESTFORGE;
                    }

                    public String version() {
                        return "1.0.0";
                    }

                    public SecurityAuditCommand prepare(SecurityAuditExecutionContext context)
                            throws Exception {
                        return new SecurityAuditCommand(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toRealPath(),
                                List.of(
                                        "-cp",
                                        Path.of(
                                                        AuditExampleProcess.class
                                                                .getProtectionDomain()
                                                                .getCodeSource()
                                                                .getLocation()
                                                                .toURI())
                                                .toRealPath()
                                                .toString(),
                                        AuditExampleProcess.class.getName()),
                                null,
                                Map.of("GET /fixture", "fixture"));
                    }
                };
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TestForgeSecurityAuditAutoConfiguration.class))
                .withBean("fixtureAdapter", SecurityAuditAdapter.class, () -> fixture)
                .withPropertyValues(
                        "forge.security-audit.enabled=true",
                        "forge.security-audit.execution.enabled=true",
                        "forge.security-audit.execution.work-dir=" + dir.resolve("private-work"),
                        "forge.security-audit.results.output-dir=" + dir.resolve("reports"))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var receipt = c.getBean(SecurityAuditExecutor.class).run(profile);
                            assertThat(receipt.attempts().getFirst().outcome())
                                    .isEqualTo(SecurityAuditExecution.Outcome.SUCCESS);
                            assertThat(receipt.assessment().healthy()).isTrue();
                            assertThat(receipt.isolation()).isEqualTo("PRIVATE_IO_NO_OS_SANDBOX");
                        });
    }
}
