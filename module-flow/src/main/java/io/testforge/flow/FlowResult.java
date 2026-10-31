package io.testforge.flow;

import java.util.List;
import java.util.Map;

public record FlowResult<S>(S reachedState, List<S> path, Map<String, Object> contextSnapshot,
                            List<FlowTransition<S>> transitions) {

    public FlowResult {
        path = List.copyOf(path);
        contextSnapshot = Map.copyOf(contextSnapshot);
        transitions = List.copyOf(transitions == null ? List.of() : transitions);
    }

    /** Without timings, as before transitions were recorded. */
    public FlowResult(S reachedState, List<S> path, Map<String, Object> contextSnapshot) {
        this(reachedState, path, contextSnapshot, List.of());
    }

    /** Total time spent in steps. */
    public java.time.Duration duration() {
        return transitions.stream().map(FlowTransition::duration).reduce(java.time.Duration.ZERO,
                java.time.Duration::plus);
    }
}
