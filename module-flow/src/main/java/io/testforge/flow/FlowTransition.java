package io.testforge.flow;

import java.time.Duration;

/**
 * One step the flow took: from a state to the state its step returned, and
 * how long the step ran. A setup that got slow says here which step did.
 */
public record FlowTransition<S>(S from, S to, Duration duration) {

    @Override
    public String toString() {
        return from + " -> " + to + " (" + duration.toMillis() + " ms)";
    }
}
