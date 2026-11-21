package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.security.audit.SecurityAuditPlan;
import io.testforge.security.audit.SecurityAuditPlanner;
import io.testforge.security.audit.SecurityAuditProfiles;
import io.testforge.security.audit.TestForgeSecurityAuditAutoConfiguration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

class SecurityAuditPlanningExampleTest {
    String json() throws Exception {
        try (var input = getClass().getResourceAsStream("/security-audit-profile.json")) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void externalSystemProfileProducesAThreeLayerPlanWithoutAnyEngineOrTarget(@TempDir Path dir)
            throws Exception {
        var profile = SecurityAuditProfiles.parse(json());
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TestForgeSecurityAuditAutoConfiguration.class))
                .withPropertyValues("forge.security-audit.enabled=true")
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            var plan = c.getBean(SecurityAuditPlanner.class).plan(profile);
                            assertThat(plan.jobs()).hasSize(28);
                            assertThat(
                                            plan.jobs().stream()
                                                    .filter(
                                                            j ->
                                                                    j.status()
                                                                            == SecurityAuditPlan
                                                                                    .Status
                                                                                    .PLANNED))
                                    .hasSize(4);
                            assertThat(plan.runnable()).isTrue();
                            assertThat(plan.executed()).isFalse();
                            try {
                                String output =
                                        new ObjectMapper()
                                                .writerWithDefaultPrettyPrinter()
                                                .writeValueAsString(plan);
                                Files.writeString(dir.resolve("plan.json"), output);
                                assertThat(output)
                                        .doesNotContain(
                                                "api.internal",
                                                "db.internal",
                                                "registry.internal",
                                                "/private/specs",
                                                "customer",
                                                "5432");
                            } catch (java.io.IOException e) {
                                throw new AssertionError(e);
                            }
                        });
    }

    @Test
    void defaultConfigurationCannotBeMistakenForAnExecutedSecurityAssessment() throws Exception {
        var profile = SecurityAuditProfiles.parse(json());
        new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(TestForgeSecurityAuditAutoConfiguration.class))
                .run(
                        c -> {
                            assertThat(c).hasNotFailed();
                            assertThatThrownBy(
                                            () ->
                                                    c.getBean(SecurityAuditPlanner.class)
                                                            .plan(profile))
                                    .hasMessageContaining("enabled must be true");
                        });
    }
}
