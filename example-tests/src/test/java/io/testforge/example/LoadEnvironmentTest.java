package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.load.LoadReport;
import io.testforge.load.LoadProperties;
import io.testforge.example.apiload.ApiLoadArtifacts;
import java.nio.file.Path;
import java.nio.file.Files;
import io.testforge.load.LoadRunner;
import io.testforge.load.TestForgeLoadAutoConfiguration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Dedicated loadTest entry point. The regular build excludes the load tag. */
@Tag("load")
class LoadEnvironmentTest {
    @Test void nativeScenarioMeetsItsThresholds() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeLoadAutoConfiguration.class))
                .withPropertyValues("forge.load.enabled=true",
                        "forge.load.environment.[API_TOKEN]=${LOAD_API_TOKEN:}",
                        "forge.load.prometheus.bearer-token=${LOAD_PROMETHEUS_TOKEN:}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    LoadProperties properties = context.getBean(LoadProperties.class);
                    String prepared = System.getProperty("testforge.api-load.prepared-dir");
                    Path provenance = prepared == null ? null : ApiLoadArtifacts.verifyPrepared(Path.of(prepared),
                            Path.of(properties.script()), properties.baseUrl());
                    LoadReport report = context.getBean(LoadRunner.class).run();
                    if (provenance != null) Files.copy(provenance, Path.of(report.directory()).resolve("provenance.json"));
                    assertThat(report.outcome()).withFailMessage("Load run %s: %s. See %s/report.md",
                            report.outcome(), report.detail(), report.directory()).isEqualTo(LoadReport.Outcome.PASSED);
                    LoggerFactory.getLogger(LoadEnvironmentTest.class).info("Load evidence: {}; dashboard: {}",
                            report.directory(), report.dashboardUrl());
                });
    }
}
