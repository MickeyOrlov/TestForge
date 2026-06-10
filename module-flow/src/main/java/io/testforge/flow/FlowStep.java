package io.testforge.flow;

public interface FlowStep<S> {

    S state();

    S execute(FlowContext context);
}
