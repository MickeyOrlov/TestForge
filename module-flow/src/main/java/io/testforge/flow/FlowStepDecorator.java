package io.testforge.flow;

import java.util.function.Supplier;

/**
 * Around-hook applied to every step of a flow: cross-cutting concerns
 * (logging, report steps, timing, metrics) live here, the steps themselves
 * stay unaware. Decorators are applied in list order — the first decorator
 * is the outermost.
 */
public interface FlowStepDecorator<S> {

    S around(FlowStep<S> step, FlowContext context, Supplier<S> proceed);
}
