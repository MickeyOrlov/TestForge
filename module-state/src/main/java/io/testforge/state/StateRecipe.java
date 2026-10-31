package io.testforge.state;

import io.testforge.flow.FlowContext;
import io.testforge.flow.FlowResult;
import io.testforge.flow.FlowStep;
import java.util.Collection;

/**
 * Adapts a product-specific setup path into a reusable state fixture. A recipe
 * describes where a domain object starts, what target state the test asked for,
 * which flow steps can move it, and how to materialize the object returned to
 * the test.
 */
public interface StateRecipe<T, S> {

    Class<T> type();

    S initialState(StateRequest request);

    S targetState(StateRequest request);

    Collection<? extends FlowStep<S>> steps(StateRequest request, FlowContext context);

    default FlowContext context(StateRequest request) {
        return new FlowContext();
    }

    T materialize(FlowResult<S> result, StateRequest request);

    /**
     * The name the recipe's flow runs under: in failure messages, the
     * flow-path artifact and {@code flow-coverage.md}. Defaults to
     * {@code state:<Type>}, so two recipes never pool their coverage.
     */
    default String name() {
        return "state:" + type().getSimpleName();
    }

    /**
     * The target tags this recipe can prepare, e.g. {@code approved},
     * {@code rejected}. Empty — the default — means "ask
     * {@link #targetState}". Once declared, an unknown tag fails with the
     * supported ones, and {@code StateRecipes.assertAllValid()} checks the
     * recipe's flow for each of them before any test needs it.
     */
    default java.util.Set<String> targets() {
        return java.util.Set.of();
    }
}
