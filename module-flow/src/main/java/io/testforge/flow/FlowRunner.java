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
    private final FlowCoverage coverage;

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
        this(name, steps, properties, clock, artifactSink, null);
    }

    FlowRunner(
            String name,
            Collection<? extends FlowStep<S>> steps,
            FlowProperties properties,
            Clock clock,
            ArtifactSink artifactSink,
            FlowCoverage coverage) {
        this.coverage = coverage;
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
        if (coverage != null) {
            coverage.run(name);
        }
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
                if (coverage != null) {
                    coverage.took(name, current, next);
                }

                if (next == null) {
                    throw new FlowException("Flow stopped before target %s. Path: %s"
                            .formatted(target, format(path)));
                }
                if (!step.declaredNext().isEmpty() && !step.declaredNext().contains(next)) {
                    throw new FlowException("Step at %s returned %s, which it does not declare (declares %s). Path: %s"
                            .formatted(current, next, sorted(step.declaredNext()), format(path)));
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

    /**
     * The flow as a Mermaid state diagram, from the declared transitions —
     * for a README, a review, or the run index. A step that declares nothing
     * shows as a transition to {@code ?}.
     */
    public String describe() {
        StringBuilder out = new StringBuilder("stateDiagram-v2\n");
        steps.values().stream().sorted(java.util.Comparator.comparing(step -> String.valueOf(step.state())))
                .forEach(step -> {
                    if (step.declaredNext().isEmpty()) {
                        out.append("    ").append(step.state()).append(" --> ").append("unknown: ?\n");
                    } else {
                        sorted(step.declaredNext()).forEach(next ->
                                out.append("    ").append(step.state()).append(" --> ").append(next).append('\n'));
                    }
                });
        return out.toString();
    }

    /**
     * What is wrong with the flow before any step runs, judged from the
     * declared transitions: a state some step can go to that has no step of
     * its own (a dead end), and a target no declared path reaches. Steps that
     * declare nothing are taken on trust. Empty when the flow is sound.
     */
    public List<String> verify(S start, S target) {
        List<String> problems = new ArrayList<>();
        java.util.Set<S> reachable = new java.util.LinkedHashSet<>();
        java.util.Deque<S> pending = new java.util.ArrayDeque<>(List.of(start));
        boolean undeclared = false;
        while (!pending.isEmpty()) {
            S state = pending.poll();
            if (!reachable.add(state) || state.equals(target)) {
                continue;
            }
            FlowStep<S> step = steps.get(state);
            if (step == null) {
                problems.add("dead end: " + state + " is reachable from " + start + " but has no step");
            } else if (step.declaredNext().isEmpty()) {
                undeclared = true;
            } else {
                pending.addAll(step.declaredNext());
            }
        }
        if (!undeclared && !reachable.contains(target)) {
            problems.add("no declared path from " + start + " reaches " + target);
        }
        return problems;
    }

    /** Fails with every problem {@link #verify} finds. */
    public void assertValid(S start, S target) {
        List<String> problems = verify(start, target);
        if (!problems.isEmpty()) {
            throw new FlowException("Flow " + name + " cannot work: " + String.join("; ", problems));
        }
    }

    private static <S> List<S> sorted(java.util.Set<S> states) {
        return states.stream().sorted(java.util.Comparator.comparing(String::valueOf)).toList();
    }

    /** {@code ; slowest step: AUTHORIZE -> READY (41000 ms)} — where a timed-out flow spent its time. */
    private String slowest(List<FlowTransition<S>> transitions) {
        return transitions.stream().max(java.util.Comparator.comparing(FlowTransition::duration))
                .map(transition -> "; slowest step: " + transition).orElse("");
    }

    /**
     * A failed run always writes its path; a successful one only with
     * {@code publish-successful-paths} — a suite runs flows hundreds of times,
     * and the run index should point at what went wrong, not drown it. The
     * suite-wide picture of what succeeded is {@code flow-coverage.md}.
     */
    private void publishArtifact(List<S> path, List<FlowTransition<S>> transitions, Throwable failure) {
        if (failure == null && !properties.publishSuccessfulPaths()) {
            return;
        }
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

