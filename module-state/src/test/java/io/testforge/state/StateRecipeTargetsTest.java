package io.testforge.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowProperties;
import io.testforge.flow.FlowResult;
import io.testforge.flow.FlowRunnerFactory;
import io.testforge.flow.FlowStep;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StateRecipeTargetsTest {

    enum Status { DRAFT, SUBMITTED, APPROVED, REJECTED, ARCHIVED }

    record Application(Status status) {
    }

    /**
     * DRAFT → SUBMITTED → APPROVED | REJECTED. Steps are built per request, so
     * each declares what it can return for this one: SUBMITTED goes to the
     * requested target only.
     */
    static class ApplicationRecipe implements StateRecipe<Application, Status> {

        @Override
        public Class<Application> type() {
            return Application.class;
        }

        @Override
        public Set<String> targets() {
            return Set.of("approved", "rejected");
        }

        @Override
        public Status initialState(StateRequest request) {
            return Status.DRAFT;
        }

        @Override
        public Status targetState(StateRequest request) {
            return Status.valueOf(request.requireTargetTag().toUpperCase(Locale.ROOT));
        }

        @Override
        public Collection<? extends FlowStep<Status>> steps(StateRequest request, FlowContext context) {
            Status target = targetState(request);
            return List.of(
                    FlowStep.of(Status.DRAFT, ctx -> Status.SUBMITTED, Status.SUBMITTED),
                    FlowStep.of(Status.SUBMITTED, ctx -> target, target));
        }

        @Override
        public Application materialize(FlowResult<Status> result, StateRequest request) {
            return new Application(result.reachedState());
        }
    }

    private final StateRecipeExecutor executor =
            new StateRecipeExecutor(new FlowRunnerFactory(new FlowProperties(null, 0, 0)), new StateProperties(null));

    @Test
    void aTypoInTheTagFailsWithTheSupportedTargets() {
        assertThatThrownBy(() -> executor.prepare(new ApplicationRecipe(), List.of("aproved")))
                .isInstanceOf(StateRecipeException.class)
                .hasMessage("Recipe state:Application cannot prepare 'aproved'; supported targets: "
                        + "[approved, rejected] (tags [aproved])");
    }

    @Test
    void aSupportedTargetIsPreparedAsBefore() {
        assertThat(executor.prepare(new ApplicationRecipe(), List.of("state:rejected", "tenant:demo")).status())
                .isEqualTo(Status.REJECTED);
    }

    @Test
    void aSoundRecipeVerifiesCleanForEveryTarget() {
        assertThat(executor.verify(new ApplicationRecipe())).isEmpty();
    }

    @Test
    void aBrokenRecipeIsFoundWithoutRunningAnything() {
        StateRecipe<Application, Status> broken = new ApplicationRecipe() {
            @Override
            public Set<String> targets() {
                return Set.of("approved", "archived", "closed");
            }

            @Override
            public Collection<? extends FlowStep<Status>> steps(StateRequest request, FlowContext context) {
                return List.of(
                        FlowStep.of(Status.DRAFT, ctx -> {
                            throw new AssertionError("verify must not execute steps");
                        }, Status.SUBMITTED),
                        FlowStep.of(Status.SUBMITTED, ctx -> Status.APPROVED, Status.APPROVED, Status.REJECTED));
            }
        };

        assertThat(executor.verify(broken)).containsExactly(
                "state:Application 'approved': dead end: REJECTED is reachable from DRAFT but has no step",
                "state:Application 'archived': dead end: APPROVED is reachable from DRAFT but has no step",
                "state:Application 'archived': dead end: REJECTED is reachable from DRAFT but has no step",
                "state:Application 'archived': no declared path from DRAFT reaches ARCHIVED",
                "state:Application 'closed': cannot be set up: No enum constant "
                        + "io.testforge.state.StateRecipeTargetsTest.Status.CLOSED");
    }

    @Test
    void stateRecipesChecksEveryRecipeAtOnce() {
        StateRecipes recipes = new StateRecipes(List.of(new ApplicationRecipe(), new ApplicationRecipe() {
            @Override
            public Set<String> targets() {
                return Set.of("closed");
            }

            @Override
            public String name() {
                return "state:Legacy";
            }
        }), executor);

        assertThatThrownBy(recipes::assertAllValid)
                .isInstanceOf(StateRecipeException.class)
                .hasMessageStartingWith("1 state recipe problems:")
                .hasMessageContaining("state:Legacy 'closed': cannot be set up: No enum constant");
    }
}
