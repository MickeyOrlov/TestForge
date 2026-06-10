package io.testforge.flow;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FlowRunner<S> {

    private final Map<S, FlowStep<S>> steps;
    private final FlowProperties properties;
    private final Clock clock;

    FlowRunner(Collection<? extends FlowStep<S>> steps, FlowProperties properties, Clock clock) {
        this.steps = new HashMap<>();
        for (FlowStep<S> step : steps) {
            FlowStep<S> previous = this.steps.put(step.state(), step);
            if (previous != null) {
                throw new FlowException("Duplicate flow step for state: " + step.state());
            }
        }
        this.properties = properties;
        this.clock = clock;
    }

    public FlowResult<S> run(S start, S target) {
        return run(start, target, new FlowContext());
    }

    public FlowResult<S> run(S start, S target, FlowContext context) {
        if (start == null) {
            throw new FlowException("start state must not be null");
        }
        if (target == null) {
            throw new FlowException("target state must not be null");
        }

        Instant deadline = clock.instant().plus(properties.timeout());
        List<S> path = new ArrayList<>();
        Map<S, Integer> visits = new HashMap<>();
        S current = start;

        for (int transition = 0; transition <= properties.maxTransitions(); transition++) {
            path.add(current);
            visits.merge(current, 1, Integer::sum);

            if (visits.get(current) > properties.maxVisitsPerState()) {
                throw new FlowException("Flow visited state too often: %s. Path: %s"
                        .formatted(current, format(path)));
            }

            if (current.equals(target)) {
                return new FlowResult<>(current, path, context.snapshot());
            }

            if (clock.instant().isAfter(deadline)) {
                throw new FlowException("Flow timeout after %s. Path: %s"
                        .formatted(properties.timeout(), format(path)));
            }

            FlowStep<S> step = steps.get(current);
            if (step == null) {
                throw new FlowException("No flow step for state: %s. Path: %s"
                        .formatted(current, format(path)));
            }

            S next;
            try {
                next = step.execute(context);
            } catch (RuntimeException e) {
                throw new FlowException("Flow step failed at state: %s. Path: %s"
                        .formatted(current, format(path)), e);
            }

            if (next == null) {
                throw new FlowException("Flow stopped before target %s. Path: %s"
                        .formatted(target, format(path)));
            }
            current = next;
        }

        throw new FlowException("Flow exceeded max transitions (%s). Path: %s"
                .formatted(properties.maxTransitions(), format(path)));
    }

    private String format(List<S> path) {
        return path.stream().map(String::valueOf).collect(Collectors.joining(" -> "));
    }
}
