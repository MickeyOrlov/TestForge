package io.testforge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlowDeclaredTransitionsTest {

    enum Approval { SUBMITTED, OPERATOR_REVIEW, MANAGER_REVIEW, APPROVED, ARCHIVED }

    private final FlowRunnerFactory flows = new FlowRunnerFactory(new FlowProperties(null, 10, 3));

    private FlowRunner<Approval> approval(FlowStep<Approval> submitted) {
        return flows.create("approval", List.of(submitted,
                FlowStep.of(Approval.OPERATOR_REVIEW, ctx -> Approval.APPROVED, Approval.APPROVED),
                FlowStep.of(Approval.MANAGER_REVIEW, ctx -> Approval.APPROVED, Approval.APPROVED)));
    }

    private static FlowStep<Approval> byAmount() {
        return FlowStep.of(Approval.SUBMITTED,
                ctx -> ctx.get("amount", Integer.class) > 1000 ? Approval.MANAGER_REVIEW : Approval.OPERATOR_REVIEW,
                Approval.MANAGER_REVIEW, Approval.OPERATOR_REVIEW);
    }

    @Test
    void aDeclaredBranchRunsAsBefore() {
        FlowContext context = new FlowContext();
        context.put("amount", 5000);

        assertThat(approval(byAmount()).run(Approval.SUBMITTED, Approval.APPROVED, context).path())
                .containsExactly(Approval.SUBMITTED, Approval.MANAGER_REVIEW, Approval.APPROVED);
    }

    @Test
    void aStepReturningAStateItDoesNotDeclareFailsAtOnce() {
        FlowRunner<Approval> runner = approval(FlowStep.of(Approval.SUBMITTED, ctx -> Approval.APPROVED,
                Approval.OPERATOR_REVIEW));

        assertThatThrownBy(() -> runner.run(Approval.SUBMITTED, Approval.APPROVED))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Step at SUBMITTED returned APPROVED, which it does not declare "
                        + "(declares [OPERATOR_REVIEW])");
    }

    @Test
    void theFlowDrawsItselfAsAMermaidDiagram() {
        assertThat(approval(byAmount()).describe()).isEqualTo("""
                stateDiagram-v2
                    MANAGER_REVIEW --> APPROVED
                    OPERATOR_REVIEW --> APPROVED
                    SUBMITTED --> MANAGER_REVIEW
                    SUBMITTED --> OPERATOR_REVIEW
                """);
    }

    @Test
    void aSoundFlowVerifiesClean() {
        assertThat(approval(byAmount()).verify(Approval.SUBMITTED, Approval.APPROVED)).isEmpty();
    }

    @Test
    void aDeadEndAndAnUnreachableTargetAreFoundBeforeRunning() {
        FlowRunner<Approval> runner = flows.create("broken", List.of(
                FlowStep.of(Approval.SUBMITTED, ctx -> Approval.OPERATOR_REVIEW,
                        Approval.OPERATOR_REVIEW, Approval.ARCHIVED),
                FlowStep.of(Approval.OPERATOR_REVIEW, ctx -> Approval.SUBMITTED, Approval.SUBMITTED)));

        assertThat(runner.verify(Approval.SUBMITTED, Approval.APPROVED)).containsExactly(
                "dead end: ARCHIVED is reachable from SUBMITTED but has no step",
                "no declared path from SUBMITTED reaches APPROVED");
        assertThatThrownBy(() -> runner.assertValid(Approval.SUBMITTED, Approval.APPROVED))
                .hasMessageStartingWith("Flow broken cannot work: dead end: ARCHIVED");
    }

    @Test
    void undeclaredStepsAreTakenOnTrust() {
        FlowStep<Approval> legacy = new FlowStep<>() {
            @Override
            public Approval state() {
                return Approval.SUBMITTED;
            }

            @Override
            public Approval execute(FlowContext context) {
                return Approval.OPERATOR_REVIEW;
            }
        };
        FlowRunner<Approval> runner = approval(legacy);

        assertThat(runner.verify(Approval.SUBMITTED, Approval.APPROVED)).isEmpty();
        assertThat(runner.describe()).contains("SUBMITTED --> unknown: ?");
    }
}
