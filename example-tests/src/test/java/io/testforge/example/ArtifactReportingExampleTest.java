package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowRunner;
import io.testforge.flow.FlowRunnerFactory;
import io.testforge.flow.FlowStep;
import io.testforge.reporting.ArtifactReportingLifecycle;
import io.testforge.reporting.ArtifactRunLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-reporting artifact collection as living documentation:
 * enabling {@code forge.reporting.artifacts.enabled=true}, executing a flow run
 * that publishes an artifact, and verifying that the run directory contains
 * {@code manifest.json} and {@code summary.md} referencing the produced artifact.
 */
@SpringBootTest(properties = {
        "forge.reporting.artifacts.enabled=true",
        "forge.reporting.artifacts.dir=build/artifact-reporting-example",
        "forge.reporting.artifacts.run-id=example-run"
})
class ArtifactReportingExampleTest {

    private static final Path BASE_DIR = Path.of("build/artifact-reporting-example");

    @Autowired
    FlowRunnerFactory flows;

    @Autowired
    ArtifactReportingLifecycle reportingLifecycle;

    @Autowired
    ArtifactRunLayout runLayout;

    @BeforeEach
    void cleanOutputDir() throws IOException {
        if (Files.exists(BASE_DIR)) {
            try (var paths = Files.walk(BASE_DIR)) {
                paths.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        });
            }
        }
    }

    @Test
    void collectsArtifactsAndProducesManifestAndSummaryFromFlowRun() throws IOException {
        // 1. Execute a flow run that publishes a flow-path artifact to ArtifactSink
        FlowRunner<OrderState> runner = flows.create("checkout", List.of(
                new Step(OrderState.CREATED, OrderState.PAID),
                new Step(OrderState.PAID, OrderState.SHIPPED)
        ));

        var result = runner.run(OrderState.CREATED, OrderState.SHIPPED);

        assertThat(result.path()).containsExactly(
                OrderState.CREATED, OrderState.PAID, OrderState.SHIPPED
        );

        // 2. Trigger lifecycle stop to flush manifest.json and summary.md
        reportingLifecycle.stop();

        // 3. Verify the produced run directory layout and files
        Path runRoot = runLayout.getRunRoot();
        Path manifestPath = runRoot.resolve("manifest.json");
        Path summaryPath = runRoot.resolve("summary.md");
        Path artifactFile = runRoot.resolve("module-flow/flow-path-checkout.txt");

        assertThat(manifestPath).exists();
        assertThat(summaryPath).exists();
        assertThat(artifactFile).exists();

        // 4. Assert manifest.json and summary.md exist and name the artifact
        String manifestContent = Files.readString(manifestPath);
        String summaryContent = Files.readString(summaryPath);
        String artifactContent = Files.readString(artifactFile);

        assertThat(manifestContent)
                .contains("example-run")
                .contains("module-flow")
                .contains("flow-path-checkout.txt");

        assertThat(summaryContent)
                .contains("example-run")
                .contains("module-flow")
                .contains("flow-path-checkout.txt");

        assertThat(artifactContent)
                .contains("Flow: checkout")
                .contains("CREATED -> PAID -> SHIPPED")
                .contains("SUCCESS");
    }

    private enum OrderState {
        CREATED,
        PAID,
        SHIPPED
    }

    private record Step(OrderState state, OrderState next) implements FlowStep<OrderState> {
        @Override
        public OrderState execute(FlowContext context) {
            return next;
        }
    }
}
