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
}
