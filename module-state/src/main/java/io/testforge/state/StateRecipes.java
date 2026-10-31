package io.testforge.state;

import java.util.ArrayList;
import java.util.List;

/**
 * Every {@link StateRecipe} bean of the context, for one test that checks them
 * all before any test needs one:
 *
 * <pre>{@code
 * @Test
 * void everyRecipeCanReachEveryTarget(@Autowired StateRecipes recipes) {
 *     recipes.assertAllValid();
 * }
 * }</pre>
 */
public class StateRecipes {

    private final List<StateRecipe<?, ?>> recipes;
    private final StateRecipeExecutor executor;

    public StateRecipes(List<StateRecipe<?, ?>> recipes, StateRecipeExecutor executor) {
        this.recipes = List.copyOf(recipes);
        this.executor = executor;
    }

    public List<StateRecipe<?, ?>> all() {
        return recipes;
    }

    /** Every problem of every recipe, see {@link StateRecipeExecutor#verify}. */
    public List<String> verify() {
        List<String> problems = new ArrayList<>();
        recipes.forEach(recipe -> problems.addAll(executor.verify(recipe)));
        return problems;
    }

    public void assertAllValid() {
        List<String> problems = verify();
        if (!problems.isEmpty()) {
            throw new StateRecipeException(problems.size() + " state recipe problems:\n  - "
                    + String.join("\n  - ", problems));
        }
    }
}
