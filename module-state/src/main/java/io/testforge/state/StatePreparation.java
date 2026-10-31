package io.testforge.state;

import io.testforge.flow.FlowResult;
import java.util.Objects;

public record StatePreparation<T, S>(
        T object,
        S initialState,
        S targetState,
        FlowResult<S> flowResult,
        StateRequest request) {

    public StatePreparation {
        object = Objects.requireNonNull(object, "object");
        initialState = Objects.requireNonNull(initialState, "initialState");
        targetState = Objects.requireNonNull(targetState, "targetState");
        flowResult = Objects.requireNonNull(flowResult, "flowResult");
        request = Objects.requireNonNull(request, "request");
    }

    /** How long the recipe's flow took: the cost of this state, step by step in {@code flowResult().transitions()}. */
    public java.time.Duration duration() {
        return flowResult.duration();
    }
}
