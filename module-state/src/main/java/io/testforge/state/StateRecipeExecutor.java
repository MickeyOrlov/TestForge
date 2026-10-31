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
        requireSupported(recipe, request);
        FlowContext context;
        S initialState;
        S targetState;
        Collection<? extends FlowStep<S>> steps;
        try {
            context = Objects.requireNonNull(recipe.context(request), "recipe context");
            initialState = Objects.requireNonNull(recipe.initialState(request), "initial state");
            targetState = Objects.requireNonNull(recipe.targetState(request), "target state");
            steps = Objects.requireNonNull(recipe.steps(request, context), "flow steps");
        } catch (StateRecipeException e) {
            throw e;
        } catch (RuntimeException e) {
            // a tag the recipe cannot map used to surface as a bare IllegalArgumentException
            throw new StateRecipeException("State recipe %s could not be set up for tags %s: %s"
                    .formatted(recipe.name(), request.tags(), e.getMessage()), e);
        }

        // named after the recipe: one "flow" bucket for every recipe made coverage and artifacts meaningless
        FlowRunner<S> runner = flowRunnerFactory.create(recipe.name(), steps);
        FlowResult<S> result;
        try {
            result = runner.run(initialState, targetState, context);
        } catch (RuntimeException e) {
            throw new StateRecipeException(
                    "State recipe %s failed for tags %s, target %s"
                            .formatted(recipe.name(), request.tags(), targetState),
                    e);
        }

        T object = recipe.materialize(result, request);
        if (object == null) {
            throw new StateRecipeException("State recipe %s reached %s for tags %s but materialized nothing"
                    .formatted(recipe.name(), result.reachedState(), request.tags()));
        }
        return new StatePreparation<>(object, initialState, targetState, result, request);
    }

    /**
     * What is wrong with {@code recipe} before it runs: for each declared
     * target, the problems {@code FlowRunner.verify} finds in the flow it would
     * run — a dead end, a target no declared transition reaches — and a target
     * the recipe cannot even map to a state. Nothing is executed; only steps
     * that declare their transitions can be judged. Empty for a recipe that
     * declares no {@link StateRecipe#targets()}.
     */
    public <T, S> List<String> verify(StateRecipe<T, S> recipe) {
        List<String> problems = new java.util.ArrayList<>();
        for (String target : new java.util.TreeSet<>(recipe.targets())) {
            StateRequest request = new StateRequest(List.of(target), properties.targetTagPrefix());
            try {
                S initial = Objects.requireNonNull(recipe.initialState(request), "initial state");
                S targetState = Objects.requireNonNull(recipe.targetState(request), "target state");
                Collection<? extends FlowStep<S>> steps = recipe.steps(request, recipe.context(request));
                flowRunnerFactory.create(recipe.name(), steps, List.of()).verify(initial, targetState)
                        .forEach(problem -> problems.add(recipe.name() + " '" + target + "': " + problem));
            } catch (RuntimeException e) {
                problems.add(recipe.name() + " '" + target + "': cannot be set up: " + e.getMessage());
            }
        }
        return problems;
    }

    private static void requireSupported(StateRecipe<?, ?> recipe, StateRequest request) {
        if (recipe.targets().isEmpty()) {
            return;
        }
        String tag = request.targetTag().orElse(null);
        if (tag == null || !recipe.targets().contains(tag)) {
            throw new StateRecipeException("Recipe %s cannot prepare %s; supported targets: %s (tags %s)"
                    .formatted(recipe.name(), tag == null ? "a state without a target tag" : "'" + tag + "'",
                            new java.util.TreeSet<>(recipe.targets()), request.tags()));
        }
    }
}
