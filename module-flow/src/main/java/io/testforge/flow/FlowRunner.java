package io.testforge.flow;

import io.testforge.artifact.ArtifactSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class FlowRunner<S> {

    private final String name;
    private final Map<S, FlowStep<S>> steps;
    private final FlowProperties properties;
    private final Clock clock;
    private final ArtifactSink artifactSink;

    FlowRunner(Collection<? extends FlowStep<S>> steps, FlowProperties properties, Clock clock) {
        this("flow", steps, properties, clock, ArtifactSink.NO_OP);
    }

    FlowRunner(Collection<? extends FlowStep<S>> steps, FlowProperties properties, Clock clock, ArtifactSink artifactSink) {
        this("flow", steps, properties, clock, artifactSink);
    }

    FlowRunner(String name, Collection<? extends FlowStep<S>> steps, FlowProperties properties, Clock clock) {
        this(name, steps, properties, clock, ArtifactSink.NO_OP);
    }

    FlowRunner(
            String name,
            Collection<? extends FlowStep<S>> steps,
            FlowProperties properties,
            Clock clock,
            ArtifactSink artifactSink) {
        this.name = (name != null && !name.isBlank()) ? name : "flow";
        this.steps = new HashMap<>();
        for (FlowStep<S> step : steps) {
            FlowStep<S> previous = this.steps.put(step.state(), step);
            if (previous != null) {
                throw new FlowException("Duplicate flow step for state: " + step.state());
            }
        }
        this.properties = properties;
        this.clock = clock;
        this.artifactSink = artifactSink != null ? artifactSink : ArtifactSink.NO_OP;
    }

    public String name() {
        return name;
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
        List<FlowTransition<S>> transitions = new ArrayList<>();
        Map<S, Integer> visits = new HashMap<>();
        S current = start;

        FlowResult<S> result = null;
        Throwable failure = null;

        try {
            for (int transition = 0; transition <= properties.maxTransitions(); transition++) {
                path.add(current);
                visits.merge(current, 1, Integer::sum);

                if (visits.get(current) > properties.maxVisitsPerState()) {
                    throw new FlowException("Flow visited state too often: %s. Path: %s"
                            .formatted(current, format(path)));
                }

                if (current.equals(target)) {
                    result = new FlowResult<>(current, path, context.snapshot(), transitions);
                    return result;
                }

                if (clock.instant().isAfter(deadline)) {
                    throw new FlowException("Flow timeout after %s. Path: %s%s"
                            .formatted(properties.timeout(), format(path), slowest(transitions)));
                }

                FlowStep<S> step = steps.get(current);
                if (step == null) {
                    throw new FlowException("No flow step for state: %s. Path: %s"
                            .formatted(current, format(path)));
                }

                S next;
                Instant started = clock.instant();
                try {
                    next = step.execute(context);
                } catch (RuntimeException e) {
                    throw new FlowException("Flow step failed at state: %s after %s ms. Path: %s"
                            .formatted(current, Duration.between(started, clock.instant()).toMillis(),
                                    format(path)), e);
                }
                transitions.add(new FlowTransition<>(current, next, Duration.between(started, clock.instant())));

                if (next == null) {
                    throw new FlowException("Flow stopped before target %s. Path: %s"
                            .formatted(target, format(path)));
                }
                current = next;
            }

            throw new FlowException("Flow exceeded max transitions (%s). Path: %s"
                    .formatted(properties.maxTransitions(), format(path)));
        } catch (Throwable t) {
            failure = t;
            throw t;
        } finally {
            publishArtifact(path, transitions, failure);
        }
    }

    /** {@code ; slowest step: AUTHORIZE -> READY (41000 ms)} — where a timed-out flow spent its time. */
    private String slowest(List<FlowTransition<S>> transitions) {
        return transitions.stream().max(java.util.Comparator.comparing(FlowTransition::duration))
                .map(transition -> "; slowest step: " + transition).orElse("");
    }

    private void publishArtifact(List<S> path, List<FlowTransition<S>> transitions, Throwable failure) {
        try {
            String outcome;
            if (failure == null) {
                outcome = "SUCCESS";
            } else {
                String errorMsg = failure.getMessage();
                if (failure.getCause() != null && failure.getCause().getMessage() != null) {
                    errorMsg += " (Cause: " + failure.getCause().getMessage() + ")";
                }
                outcome = "FAILED (" + errorMsg + ")";
            }
            StringBuilder steps = new StringBuilder();
            transitions.forEach(transition -> steps.append("  ").append(transition).append('\n'));
            String content = """
                    Flow: %s
                    Path: %s
                    Outcome: %s
                    Steps:
                    %s""".formatted(name, format(path), outcome, steps);
            String artifactName = "flow-path-" + name + ".txt";
            artifactSink.write("module-flow", "flow-path", artifactName, "text/plain", content);
        } catch (Throwable ignored) {
            // Publishing must be best-effort and never throw or suppress the original exception
        }
    }

    private String format(List<S> path) {
        return path.stream().map(String::valueOf).collect(Collectors.joining(" -> "));
    }
}

