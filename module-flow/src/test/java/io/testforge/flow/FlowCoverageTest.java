package io.testforge.flow;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlowCoverageTest {

    enum Approval { SUBMITTED, OPERATOR_REVIEW, MANAGER_REVIEW, APPROVED }

    @TempDir
    Path runDir;

    private static List<FlowStep<Approval>> approval() {
        return List.of(
                FlowStep.of(Approval.SUBMITTED,
                        ctx -> ctx.get("amount", Integer.class) > 1000 ? Approval.MANAGER_REVIEW : Approval.OPERATOR_REVIEW,
                        Approval.MANAGER_REVIEW, Approval.OPERATOR_REVIEW),
                FlowStep.of(Approval.OPERATOR_REVIEW, ctx -> Approval.APPROVED, Approval.APPROVED),
                FlowStep.of(Approval.MANAGER_REVIEW, ctx -> Approval.APPROVED, Approval.APPROVED));
    }

    private static void runWithAmount(FlowRunnerFactory factory, int amount) {
        FlowContext context = new FlowContext();
        context.put("amount", amount);
        // a fresh runner per test, as tests usually build them: coverage is kept by name
        factory.create("approval", approval(), List.of()).run(Approval.SUBMITTED, Approval.APPROVED, context);
    }

    @Test
    void aBranchNoRunTookIsReportedAcrossRunners() {
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 10, 3));

        runWithAmount(factory, 10);
        runWithAmount(factory, 20);

        assertThat(factory.coverage().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.flow()).isEqualTo("approval");
            assertThat(entry.runs()).isEqualTo(2);
            assertThat(entry.declared()).hasSize(4);
            assertThat(entry.uncovered()).containsExactly(
                    "MANAGER_REVIEW -> APPROVED", "SUBMITTED -> MANAGER_REVIEW");
        });
    }

    @Test
    void takingEveryBranchLeavesNothingUncovered() {
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 10, 3));

        runWithAmount(factory, 10);
        runWithAmount(factory, 5000);

        assertThat(factory.coverage().entries().get(0).uncovered()).isEmpty();
    }

    @Test
    void theReportGoesToTheRunIndexReportOnly() throws Exception {
        List<TestArtifact> registered = new CopyOnWriteArrayList<>();
        ArtifactSink sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return runDir.resolve(source);
            }

            @Override
            public void register(TestArtifact artifact) {
                registered.add(artifact);
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
            }
        };
        FlowCoverage coverage = new FlowCoverage();
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 10, 3), Clock.systemUTC(), sink,
                coverage);
        runWithAmount(factory, 10);

        new FlowCoverageReporter(coverage, sink).stop();

        assertThat(registered).extracting(TestArtifact::name).containsExactly("flow-coverage.md", "flow-coverage.json");
        assertThat(registered.getFirst().metadata()).containsEntry("healthy", "true").containsEntry("uncovered", "2");
        assertThat(Files.readString(runDir.resolve("module-flow/flow-coverage.json")))
                .contains("\"formatVersion\" : 1")
                .contains("\"flow\" : \"approval\"")
                .contains("\"declared\" : 4")
                .contains("\"SUBMITTED -> MANAGER_REVIEW\"");
        assertThat(Files.readString(runDir.resolve("module-flow/flow-coverage.md")))
                .contains("## approval")
                .contains("declared transitions taken: 2 of 4")
                .contains("- `SUBMITTED -> MANAGER_REVIEW`");
    }

    @Test
    void nothingIsWrittenWhenNoFlowRan() {
        List<TestArtifact> registered = new CopyOnWriteArrayList<>();
        new FlowCoverageReporter(new FlowCoverage(), new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return runDir;
            }

            @Override
            public void register(TestArtifact artifact) {
                registered.add(artifact);
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return null;
            }
        }).stop();

        assertThat(registered).isEmpty();
    }
}
