package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowException;
import io.testforge.flow.FlowRunner;
import io.testforge.flow.FlowRunnerFactory;
import io.testforge.flow.FlowStep;
import io.testforge.flow.FlowStepDecorator;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
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

    @Test
    void roleBasedApprovalPath() {
        FlowRunner<ApprovalState> runner = flows.create(List.of(
                new RoleBranchStep(ApprovalState.SUBMITTED),
                new ApprovalStep(ApprovalState.OPERATOR_REVIEW, ApprovalState.APPROVED),
                new ApprovalStep(ApprovalState.MANAGER_REVIEW, ApprovalState.APPROVED)));

        FlowContext operatorContext = new FlowContext();
        operatorContext.put("role", "OPERATOR");
        var operatorResult = runner.run(ApprovalState.SUBMITTED, ApprovalState.APPROVED, operatorContext);
        assertThat(operatorResult.path()).containsExactly(
                ApprovalState.SUBMITTED,
                ApprovalState.OPERATOR_REVIEW,
                ApprovalState.APPROVED);

        FlowContext managerContext = new FlowContext();
        managerContext.put("role", "MANAGER");
        var managerResult = runner.run(ApprovalState.SUBMITTED, ApprovalState.APPROVED, managerContext);
        assertThat(managerResult.path()).containsExactly(
                ApprovalState.SUBMITTED,
                ApprovalState.MANAGER_REVIEW,
                ApprovalState.APPROVED);
    }

    @Test
    void decoratorWrapsEveryStepWithoutTouchingThem() {
        List<String> transitions = new ArrayList<>();
        FlowStepDecorator<DemoState> recording = new FlowStepDecorator<>() {
            @Override
            public DemoState around(FlowStep<DemoState> step, FlowContext context,
                                    Supplier<DemoState> proceed) {
                DemoState next = proceed.get();
                transitions.add(step.state() + "->" + next);
                return next;
            }
        };

        FlowRunner<DemoState> runner = flows.create(
                List.of(
                        new Step(DemoState.START, DemoState.AUTHORIZE),
                        new Step(DemoState.AUTHORIZE, DemoState.READY)),
                List.of(recording));

        runner.run(DemoState.START, DemoState.READY);

        assertThat(transitions).containsExactly("START->AUTHORIZE", "AUTHORIZE->READY");
    }

    private enum DemoState {
        START,
        AUTHORIZE,
        CREATE_RECORD,
        READY,
        LOOP
    }

    private enum ApprovalState {
        SUBMITTED,
        OPERATOR_REVIEW,
        MANAGER_REVIEW,
        APPROVED
    }

    /** Branches like a real approval flow: path depends on actor role in context. */
    private record RoleBranchStep(ApprovalState state) implements FlowStep<ApprovalState> {

        @Override
        public ApprovalState execute(FlowContext context) {
            String role = context.get("role", String.class);
            return "MANAGER".equals(role) ? ApprovalState.MANAGER_REVIEW : ApprovalState.OPERATOR_REVIEW;
        }
    }

    private record ApprovalStep(ApprovalState state, ApprovalState next) implements FlowStep<ApprovalState> {

        @Override
        public ApprovalState execute(FlowContext context) {
            return next;
        }
    }

    private record Step(DemoState state, DemoState next) implements FlowStep<DemoState> {

        @Override
        public DemoState execute(FlowContext context) {
            context.put("visited." + state.name(), true);
            return next;
        }
    }
}
