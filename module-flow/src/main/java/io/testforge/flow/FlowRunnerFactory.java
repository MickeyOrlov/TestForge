package io.testforge.flow;

import java.time.Clock;
import java.util.Collection;

public class FlowRunnerFactory {

    private final FlowProperties properties;
    private final Clock clock;

    public FlowRunnerFactory(FlowProperties properties) {
        this(properties, Clock.systemUTC());
    }

    FlowRunnerFactory(FlowProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public <S> FlowRunner<S> create(Collection<? extends FlowStep<S>> steps) {
        return new FlowRunner<>(steps, properties, clock);
    }
}
