package io.testforge.flow;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

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
        return create(steps, List.of(new LoggingFlowStepDecorator<>()));
    }

    /**
     * Decorators are applied in list order: the first decorator is the
     * outermost. Pass an empty list for raw, undecorated steps.
     */
    public <S> FlowRunner<S> create(
            Collection<? extends FlowStep<S>> steps,
            List<? extends FlowStepDecorator<S>> decorators) {
        List<FlowStep<S>> decorated = new ArrayList<>(steps.size());
        for (FlowStep<S> step : steps) {
            decorated.add(decorate(step, decorators));
        }
        return new FlowRunner<>(decorated, properties, clock);
    }

    private static <S> FlowStep<S> decorate(
            FlowStep<S> step,
            List<? extends FlowStepDecorator<S>> decorators) {
        FlowStep<S> result = step;
        for (int i = decorators.size() - 1; i >= 0; i--) {
            FlowStepDecorator<S> decorator = decorators.get(i);
            FlowStep<S> inner = result;
            result = new FlowStep<>() {
                @Override
                public S state() {
                    return step.state();
                }

                @Override
                public S execute(FlowContext context) {
                    Supplier<S> proceed = () -> inner.execute(context);
                    return decorator.around(step, context, proceed);
                }
            };
        }
        return result;
    }
}
