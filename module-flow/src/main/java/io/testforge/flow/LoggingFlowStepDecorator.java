package io.testforge.flow;

import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default decorator: logs every transition with its duration, so a flow run
 * reads as a timeline in the test log.
 */
public class LoggingFlowStepDecorator<S> implements FlowStepDecorator<S> {

    private static final Logger log = LoggerFactory.getLogger(LoggingFlowStepDecorator.class);

    @Override
    public S around(FlowStep<S> step, FlowContext context, Supplier<S> proceed) {
        long started = System.currentTimeMillis();
        S next = proceed.get();
        log.info("Flow step {} -> {} ({} ms)", step.state(), next, System.currentTimeMillis() - started);
        return next;
    }
}
