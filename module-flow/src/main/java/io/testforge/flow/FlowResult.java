package io.testforge.flow;

import java.util.List;
import java.util.Map;

public record FlowResult<S>(S reachedState, List<S> path, Map<String, Object> contextSnapshot) {

    public FlowResult {
        path = List.copyOf(path);
        contextSnapshot = Map.copyOf(contextSnapshot);
    }
}
