package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowException;
import io.testforge.flow.FlowRunner;
import io.testforge.flow.FlowRunnerFactory;
import io.testforge.flow.FlowStep;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-flow: encode a long business path as small deterministic
 * steps, then let the runner report the exact state path when something drifts.
 */
@SpringBootTest
class FlowRunnerTest {

    @Autowired
    FlowRunnerFactory flows;

    @Test
    void runsScenarioUntilTargetState() {
        FlowRunner<DemoState> runner = flows.create(List.of(
                new Step(DemoState.START, DemoState.AUTHORIZE),
                new Step(DemoState.AUTHORIZE, DemoState.CREATE_RECORD),
                new Step(DemoState.CREATE_RECORD, DemoState.READY)));

        var result = runner.run(DemoState.START, DemoState.READY);

        assertThat(result.path()).containsExactly(
                DemoState.START,
                DemoState.AUTHORIZE,
                DemoState.CREATE_RECORD,
                DemoState.READY);
        assertThat(result.contextSnapshot())
                .containsEntry("visited.AUTHORIZE", true)
                .containsEntry("visited.CREATE_RECORD", true);
    }

    @Test
    void failsWithReadablePathWhenFlowCycles() {
        FlowRunner<DemoState> runner = flows.create(List.of(
                new Step(DemoState.LOOP, DemoState.LOOP)));

        assertThatThrownBy(() -> runner.run(DemoState.LOOP, DemoState.READY))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Flow visited state too often")
                .hasMessageContaining("LOOP -> LOOP");
    }

    private enum DemoState {
        START,
        AUTHORIZE,
        CREATE_RECORD,
        READY,
        LOOP
    }

    private record Step(DemoState state, DemoState next) implements FlowStep<DemoState> {

        @Override
        public DemoState execute(FlowContext context) {
            context.put("visited." + state.name(), true);
            return next;
        }
    }
}
