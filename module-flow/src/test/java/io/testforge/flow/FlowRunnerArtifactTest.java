package io.testforge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlowRunnerArtifactTest {

    @Test
    void publishesFlowPathArtifactOnSuccessfulRun() {
        RecordingArtifactSink fakeSink = new RecordingArtifactSink();
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 100, 5), fakeSink);

        FlowRunner<DemoState> runner = factory.create("demo-flow", List.of(
                new SimpleStep(DemoState.START, DemoState.AUTHORIZE),
                new SimpleStep(DemoState.AUTHORIZE, DemoState.READY)
        ));

        FlowResult<DemoState> result = runner.run(DemoState.START, DemoState.READY);

        assertThat(result.path()).containsExactly(DemoState.START, DemoState.AUTHORIZE, DemoState.READY);

        assertThat(fakeSink.written).hasSize(1);
        RecordingArtifactSink.WrittenCall call = fakeSink.written.get(0);
        assertThat(call.source()).isEqualTo("module-flow");
        assertThat(call.category()).isEqualTo("flow-path");
        assertThat(call.name()).isEqualTo("flow-path-demo-flow.txt");
        assertThat(call.mediaType()).isEqualTo("text/plain");
        assertThat(call.content())
                .contains("Flow: demo-flow")
                .contains("Path: START -> AUTHORIZE -> READY")
                .contains("Outcome: SUCCESS");
    }

    @Test
    void publishesFlowPathArtifactOnFailingRunAndPreservesOriginalException() {
        RecordingArtifactSink fakeSink = new RecordingArtifactSink();
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 100, 5), fakeSink);

        IllegalStateException originalCause = new IllegalStateException("Database connection lost");
        FlowRunner<DemoState> runner = factory.create("failing-flow", List.of(
                new SimpleStep(DemoState.START, DemoState.AUTHORIZE),
                new FailingStep(DemoState.AUTHORIZE, originalCause)
        ));

        assertThatThrownBy(() -> runner.run(DemoState.START, DemoState.READY))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Flow step failed at state: AUTHORIZE")
                .hasMessageContaining("START -> AUTHORIZE")
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("Database connection lost");

        assertThat(fakeSink.written).hasSize(1);
        RecordingArtifactSink.WrittenCall call = fakeSink.written.get(0);
        assertThat(call.source()).isEqualTo("module-flow");
        assertThat(call.category()).isEqualTo("flow-path");
        assertThat(call.name()).isEqualTo("flow-path-failing-flow.txt");
        assertThat(call.content())
                .contains("Flow: failing-flow")
                .contains("Path: START -> AUTHORIZE")
                .contains("Outcome: FAILED")
                .contains("Database connection lost");
    }

    @Test
    void doesNotPublishOrThrowWithNoOpArtifactSink() {
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 100, 5), ArtifactSink.NO_OP);

        FlowRunner<DemoState> runner = factory.create("noop-flow", List.of(
                new SimpleStep(DemoState.START, DemoState.AUTHORIZE),
                new SimpleStep(DemoState.AUTHORIZE, DemoState.READY)
        ));

        FlowResult<DemoState> result = runner.run(DemoState.START, DemoState.READY);
        assertThat(result.path()).containsExactly(DemoState.START, DemoState.AUTHORIZE, DemoState.READY);

        FlowRunner<DemoState> failingRunner = factory.create("noop-failing-flow", List.of(
                new FailingStep(DemoState.START, new RuntimeException("Error in step"))
        ));

        assertThatThrownBy(() -> failingRunner.run(DemoState.START, DemoState.READY))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Flow step failed at state: START");
    }

    @Test
    void publishesWithCustomFlowNameWithoutClashing() {
        RecordingArtifactSink fakeSink = new RecordingArtifactSink();
        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 100, 5), fakeSink);

        FlowRunner<DemoState> flow1 = factory.create("checkout-flow", List.of(
                new SimpleStep(DemoState.START, DemoState.READY)
        ));

        FlowRunner<DemoState> flow2 = factory.create("payment-flow", List.of(
                new SimpleStep(DemoState.START, DemoState.READY)
        ));

        flow1.run(DemoState.START, DemoState.READY);
        flow2.run(DemoState.START, DemoState.READY);

        assertThat(fakeSink.written).hasSize(2);
        assertThat(fakeSink.written.get(0).name()).isEqualTo("flow-path-checkout-flow.txt");
        assertThat(fakeSink.written.get(1).name()).isEqualTo("flow-path-payment-flow.txt");
    }

    @Test
    void faultyArtifactSinkDoesNotImpactFlowExecutionOrFailure() {
        ArtifactSink faultySink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return Path.of("/invalid/path");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new RuntimeException("Sink registration error");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new RuntimeException("Sink disk write error");
            }
        };

        FlowRunnerFactory factory = new FlowRunnerFactory(new FlowProperties(null, 100, 5), faultySink);

        FlowRunner<DemoState> successRunner = factory.create("faulty-success", List.of(
                new SimpleStep(DemoState.START, DemoState.READY)
        ));

        FlowResult<DemoState> result = successRunner.run(DemoState.START, DemoState.READY);
        assertThat(result.path()).containsExactly(DemoState.START, DemoState.READY);

        FlowRunner<DemoState> failingRunner = factory.create("faulty-failing", List.of(
                new FailingStep(DemoState.START, new IllegalStateException("Business exception"))
        ));

        assertThatThrownBy(() -> failingRunner.run(DemoState.START, DemoState.READY))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Flow step failed at state: START")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    private enum DemoState {
        START,
        AUTHORIZE,
        READY
    }

    private record SimpleStep(DemoState state, DemoState next) implements FlowStep<DemoState> {
        @Override
        public DemoState execute(FlowContext context) {
            return next;
        }
    }

    private record FailingStep(DemoState state, RuntimeException exceptionToThrow) implements FlowStep<DemoState> {
        @Override
        public DemoState execute(FlowContext context) {
            throw exceptionToThrow;
        }
    }

    private static class RecordingArtifactSink implements ArtifactSink {
        final List<WrittenCall> written = new ArrayList<>();
        final List<TestArtifact> registered = new ArrayList<>();

        record WrittenCall(String source, String category, String name, String mediaType, String content) {}

        @Override
        public Path directoryFor(String source) {
            return Path.of(System.getProperty("java.io.tmpdir"), "test-sink");
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            written.add(new WrittenCall(source, category, name, mediaType, content));
            Path file = directoryFor(source).resolve(name != null ? name : "artifact.tmp");
            TestArtifact artifact = new TestArtifact(source, category, name, file, mediaType, Instant.now(), Map.of());
            registered.add(artifact);
            return artifact;
        }
    }
}
