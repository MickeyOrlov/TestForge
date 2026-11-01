package io.testforge.gherkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource;

import io.testforge.gherkin.fixture.DiagnosticsFixture;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

/** A real failing scenario through the Cucumber engine, so the hooks run as they do in a project. */
class ScenarioDiagnosticsTest {

    @Test
    void aFailedScenarioLeavesItsOwnKafkaTrafficWithoutBodies() {
        DiagnosticsFixture.WRITTEN.clear();

        EngineTestKit.engine("cucumber")
                .selectors(selectClasspathResource("diagnostics/failing.feature"))
                .configurationParameter("cucumber.glue",
                        "io.testforge.gherkin.glue,io.testforge.gherkin.kafka,io.testforge.gherkin.fixture")
                .configurationParameter("cucumber.publish.quiet", "true")
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.failed(1));

        assertThat(DiagnosticsFixture.WRITTEN).singleElement().satisfies(written -> assertThat(written)
                .startsWith("module-gherkin/failed-scenario-kafka/paying-is-rejected-L3-kafka.md\n")
                .contains("# Kafka traffic before `Paying is rejected` failed")
                .contains("Failure: see the failed step of classpath:diagnostics/failing.feature:3")
                .contains("## Published by the test (1)")
                .contains("`orders.commands/0@")
                .contains("## Received while it ran (1; only messages with X-Test-Scope=bdd-")
                .contains("`orders.events/0@3` key=o-1")
                .as("another scenario's message").doesNotContain("key=o-9")
                .as("no bodies by default").doesNotContain("4111").doesNotContain("REJECTED"));
    }
}
