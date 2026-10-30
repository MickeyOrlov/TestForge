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
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class StateRecipeExecutorTest {

    enum Status { DRAFT, SUBMITTED, APPROVED, REJECTED }

    record Application(String id, Status status) {
    }

    static FlowStep<Status> step(Status from, Function<FlowContext, Status> action) {
        return new FlowStep<>() {
            @Override
            public Status state() {
                return from;
            }

            @Override
            public Status execute(FlowContext context) {
                return action.apply(context);
            }
        };
    }

    /** DRAFT → SUBMITTED → APPROVED|REJECTED, target picked from the request tag. */
    static class ApplicationRecipe implements StateRecipe<Application, Status> {
        @Override
        public Class<Application> type() {
            return Application.class;
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
                    step(Status.DRAFT, ctx -> {
                        ctx.put("id", "app-" + request.tags().size());
                        return Status.SUBMITTED;
                    }),
                    step(Status.SUBMITTED, ctx -> target));
        }

        @Override
        public Application materialize(FlowResult<Status> result, StateRequest request) {
            return new Application((String) result.contextSnapshot().get("id"), result.reachedState());
        }
    }

    private final StateRecipeExecutor executor =
            new StateRecipeExecutor(new FlowRunnerFactory(new FlowProperties(null, 0, 0)), new StateProperties(null));

    @Test
    void drivesTheObjectToTheTaggedStateAndReportsThePath() {
        StatePreparation<Application, Status> preparation =
                executor.prepareDetailed(new ApplicationRecipe(), List.of("state:approved", "tenant:demo"));

        assertThat(preparation.object()).isEqualTo(new Application("app-2", Status.APPROVED));
        assertThat(preparation.initialState()).isEqualTo(Status.DRAFT);
        assertThat(preparation.targetState()).isEqualTo(Status.APPROVED);
        assertThat(preparation.flowResult().path())
                .containsExactly(Status.DRAFT, Status.SUBMITTED, Status.APPROVED);
        assertThat(preparation.request().hasTag("tenant:demo")).isTrue();
    }

    @Test
    void differentTagsReachDifferentStates() {
        assertThat(executor.prepare(new ApplicationRecipe(), List.of("rejected")).status())
                .isEqualTo(Status.REJECTED);
    }

    @Test
    void aFailingStepIsWrappedWithTypeTagsAndTarget() {
        StateRecipe<Application, Status> broken = new ApplicationRecipe() {
            @Override
            public Collection<? extends FlowStep<Status>> steps(StateRequest request, FlowContext context) {
                return List.of(step(Status.DRAFT, ctx -> {
                    throw new IllegalStateException("submit endpoint returned 500");
                }));
            }
        };

        assertThatThrownBy(() -> executor.prepare(broken, List.of("approved")))
                .isInstanceOf(StateRecipeException.class)
                .hasMessageContaining("Application")
                .hasMessageContaining("[approved]")
                .hasMessageContaining("APPROVED")
                .hasRootCauseMessage("submit endpoint returned 500");
    }

    @Test
    void aRecipeThatMaterializesNothingFailsLoudly() {
        StateRecipe<Application, Status> empty = new ApplicationRecipe() {
            @Override
            public Application materialize(FlowResult<Status> result, StateRequest request) {
                return null;
            }
        };

        assertThatThrownBy(() -> executor.prepare(empty, List.of("approved")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("materialized object");
    }

    @Test
    void providerAdaptsTheRecipeToThePreparedDataSpi() {
        var provider = StatePreparedDataProvider.of(new ApplicationRecipe(), executor);

        assertThat(provider.type()).isEqualTo(Application.class);
        assertThat(provider.prepare(List.of("approved")).status()).isEqualTo(Status.APPROVED);
    }
}
