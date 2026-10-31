package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.data.cleanup.CleanupExtension;
import io.testforge.data.cleanup.LeftoverReporter;
import io.testforge.data.factory.Factory;
import io.testforge.data.isolation.DataIsolationExtension;
import io.testforge.reporting.ArtifactReportingLifecycle;
import io.testforge.reporting.ArtifactRunLayout;
import java.nio.file.Files;
import java.util.Map;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * When an undo fails — the environment answered 409, the object is locked —
 * the test fails, and the run index says exactly what was left behind, with
 * the isolation tag the data carries, so a sweeper or a person can remove it.
 */
@SpringBootTest(properties = {
        "forge.reporting.artifacts.enabled=true",
        "forge.reporting.artifacts.dir=build/leftover-report-example",
        "forge.reporting.artifacts.run-id=leftover-run"
})
class LeftoverReportExampleTest {

    /** A test whose cleanup cannot undo what it created; run below through EngineTestKit. */
    @ExtendWith({DataIsolationExtension.class, CleanupExtension.class})
    static class LockedOrderTestkitFixture {

        static final Factory<Map<String, Object>> ORDERS = Factory.map("order")
                .field("reference", f -> f.tagged());

        @Test
        void createsAnOrderThatCannotBeDeleted() {
            ORDERS.create(order -> (String) order.get("reference"), reference -> {
                throw new IllegalStateException("409 order " + reference + " is locked");
            });
        }
    }

    @Autowired
    LeftoverReporter leftoverReporter;

    @Autowired
    ArtifactReportingLifecycle reportingLifecycle;

    @Autowired
    ArtifactRunLayout runLayout;

    @Test
    void dataACleanupCouldNotRemoveIsListedInTheRunIndex() throws Exception {
        EngineTestKit.engine("junit-jupiter").selectors(selectClass(LockedOrderTestkitFixture.class)).execute()
                .testEvents().assertStatistics(stats -> stats.failed(1));

        leftoverReporter.stop();
        reportingLifecycle.stop();

        JsonNode report = new ObjectMapper().readTree(runLayout.runRoot().resolve("module-data/leftovers.json").toFile());
        JsonNode leftover = report.get("leftovers").get(0);
        assertThat(leftover.get("test").asText()).isEqualTo("LockedOrderTestkitFixture > createsAnOrderThatCannotBeDeleted()");
        assertThat(leftover.get("description").asText()).startsWith("order " + leftover.get("scopeTag").asText());
        assertThat(leftover.get("error").asText()).contains("is locked");

        JsonNode manifest = new ObjectMapper().readTree(runLayout.runRoot().resolve("manifest.json").toFile());
        assertThat(StreamSupport.stream(manifest.get("artifacts").spliterator(), false)
                .filter(entry -> entry.get("name").asText().equals("leftovers.json")))
                .singleElement().satisfies(entry ->
                        assertThat(entry.get("metadata").get("healthy").asText()).isEqualTo("false"));
        assertThat(Files.readString(runLayout.runRoot().resolve("summary.md")))
                .contains("Needs attention")
                .contains("leftovers.json");
    }
}
