package io.testforge.state;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowResult;
import io.testforge.flow.FlowRunner;
import io.testforge.flow.FlowRunnerFactory;
import io.testforge.flow.FlowStep;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public class StateRecipeExecutor {

    private final FlowRunnerFactory flowRunnerFactory;
    private final StateProperties properties;

    public StateRecipeExecutor(FlowRunnerFactory flowRunnerFactory, StateProperties properties) {
        this.flowRunnerFactory = flowRunnerFactory;
        this.properties = properties;
    }

    public <T, S> T prepare(StateRecipe<T, S> recipe, List<String> tags) {
        return prepareDetailed(recipe, tags).object();
    }

    public <T, S> StatePreparation<T, S> prepareDetailed(StateRecipe<T, S> recipe, List<String> tags) {
        Objects.requireNonNull(recipe, "recipe");
        StateRequest request = new StateRequest(tags, properties.targetTagPrefix());
        FlowContext context = Objects.requireNonNull(recipe.context(request), "recipe context");
        S initialState = Objects.requireNonNull(recipe.initialState(request), "initial state");
        S targetState = Objects.requireNonNull(recipe.targetState(request), "target state");
        Collection<? extends FlowStep<S>> steps = Objects.requireNonNull(recipe.steps(request, context), "flow steps");

        FlowRunner<S> runner = flowRunnerFactory.create(steps);
        FlowResult<S> result;
        try {
            result = runner.run(initialState, targetState, context);
        } catch (RuntimeException e) {
            throw new StateRecipeException(
                    "State recipe failed for %s, tags %s, target %s"
                            .formatted(recipe.type().getSimpleName(), request.tags(), targetState),
                    e);
        }

        T object = Objects.requireNonNull(recipe.materialize(result, request), "materialized object");
        return new StatePreparation<>(object, initialState, targetState, result, request);
    }
}
