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
}
