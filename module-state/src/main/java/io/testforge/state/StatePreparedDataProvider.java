package io.testforge.state;

import io.testforge.data.prepared.PreparedDataProvider;
import java.util.List;

/**
 * Adapter from state recipes to {@code @Prepared}. Register one bean per domain
 * type in the adapted project and tests can request states by tags.
 */
public class StatePreparedDataProvider<T, S> implements PreparedDataProvider<T> {

    private final StateRecipe<T, S> recipe;
    private final StateRecipeExecutor executor;

    public StatePreparedDataProvider(StateRecipe<T, S> recipe, StateRecipeExecutor executor) {
        this.recipe = recipe;
        this.executor = executor;
    }

    public static <T, S> StatePreparedDataProvider<T, S> of(
            StateRecipe<T, S> recipe,
            StateRecipeExecutor executor) {
        return new StatePreparedDataProvider<>(recipe, executor);
    }

    @Override
    public Class<T> type() {
        return recipe.type();
    }

    @Override
    public T prepare(List<String> tags) {
        return executor.prepare(recipe, tags);
    }
}
