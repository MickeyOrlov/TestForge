package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.security.audit.SecurityAuditPlanner;
import io.testforge.security.audit.SecurityAuditProfiles;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PublishedSecurityAuditSmokeTest {
    @Test
    void publishedJarRegistersItsAutoConfigurationAndPlansWithoutScannerDependencies() {
        var profile =
                SecurityAuditProfiles.parse(
                        """
{"id":"internal","environment":"LOCAL","allowedHosts":["127.0.0.1"],
 "assets":[{"id":"network","layer":"NETWORK","kind":"HOST","location":"127.0.0.1","ports":[8443]}],
 "engines":[{"engine":"NMAP","enabled":true,"version":"7.95"}]}
""");
        assertThat(
                        ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader())
                                .getCandidates())
                .contains("io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration");
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration
                                        .class))
                .withPropertyValues("forge.security-audit.enabled=true")
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var plan = c.getBean(SecurityAuditPlanner.class).plan(profile);
                            assertThat(plan.jobs()).hasSize(7);
                            assertThat(plan.runnable()).isTrue();
                            assertThat(plan.executed()).isFalse();
                        });
    }

    @Test
    void existingNormalizedSecurityReportCanBeImportedWithoutSecurityClasses(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        if (Boolean.getBoolean("audit.standalone")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> Class.forName("io.testforge.security.SecurityRunner"))
                    .isInstanceOf(ClassNotFoundException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> Class.forName("io.testforge.http.ApiClient"))
                    .isInstanceOf(ClassNotFoundException.class);
        }
        var profile =
                SecurityAuditProfiles.parse(
                        """
{"id":"internal","environment":"TEST","allowedHosts":["api.internal"],
 "assets":[{"id":"api","layer":"APPLICATION","kind":"URL","location":"https://api.internal"}],
 "engines":[{"engine":"TESTFORGE","enabled":true,"version":"1.3.0-SNAPSHOT"}]}
""");
        byte[] source =
                """
{"formatVersion":1,"enabled":true,"measured":true,"complete":true,"healthy":true,"findings":[],
 "coverage":[{"check":"unauthenticated","target":"GET /private","role":"anonymous","status":"measured","reason":null}],
 "newCounts":{"critical":0,"high":0,"medium":0,"low":0,"info":0}}
"""
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var directory = temporary.toRealPath();
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration
                                        .class))
                .withPropertyValues(
                        "forge.security-audit.enabled=true",
                        "forge.security-audit.results.output-dir=" + directory)
                .run(
                        c -> {
                            var evidence =
                                    c.getBean(
                                                    io.testforge.security.audit.SecurityAuditImports
                                                            .class)
                                            .securityReport(
                                                    "api",
                                                    "1.3.0-SNAPSHOT",
                                                    source,
                                                    java.util.Map.of("GET /private", "resource"));
                            var report =
                                    c.getBean(io.testforge.security.audit.SecurityAuditRunner.class)
                                            .run(
                                                    c.getBean(SecurityAuditPlanner.class)
                                                            .plan(profile),
                                                    java.util.List.of(evidence));
                            assertThat(report.healthy()).isTrue();
                        });
    }

    @Test
    void publishedCliPlansWithoutAdaptersAndRefusesToPretendAnEmptyRegistryIsAScan(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path temporary) throws Exception {
        var directory = temporary.toRealPath();
        var profile = directory.resolve("profile.json");
        java.nio.file.Files.writeString(
                profile,
                """
{"id":"internal","environment":"LOCAL","allowedHosts":["127.0.0.1"],
 "assets":[{"id":"api","layer":"APPLICATION","kind":"URL","location":"http://127.0.0.1"}],
 "engines":[{"engine":"TESTFORGE","enabled":true,"version":"1.0.0"}]}
""");
        var cli = new io.testforge.security.audit.SecurityAuditCli();
        assertThat(
                        cli.execute(
                                "plan",
                                "--profile",
                                profile.toString(),
                                "--output",
                                directory.resolve("plan").toString()))
                .isZero();
        assertThat(
                        cli.execute(
                                "run",
                                "--enable-run",
                                "--profile",
                                profile.toString(),
                                "--output",
                                directory.resolve("run").toString()))
                .isEqualTo(3);
        try (var files = java.nio.file.Files.walk(directory.resolve("run"))) {
            var receipts =
                    files.filter(
                                    p ->
                                            p.getFileName()
                                                    .toString()
                                                    .equals("security-audit-execution.json"))
                            .toList();
            assertThat(receipts).hasSize(1);
            assertThat(java.nio.file.Files.readString(receipts.getFirst()))
                    .contains("ADAPTER_MISSING")
                    .doesNotContain("127.0.0.1");
        }
    }
}
