package io.testforge.flow;

import io.qameta.allure.Allure;
import java.util.function.Supplier;

/**
 * Reports every flow transition as an Allure step, so a long preparation
 * path reads as a collapsible timeline in the report.
 *
 * <p>Optional dependency: compiled against
 * {@code io.qameta.allure:allure-java-commons}, which must be on the runtime
 * classpath of the test module that references this decorator. The rest of
 * module-flow works without Allure.
 */
public class AllureFlowStepDecorator<S> implements FlowStepDecorator<S> {

    @Override
    public S around(FlowStep<S> step, FlowContext context, Supplier<S> proceed) {
        return Allure.step("Flow step: " + step.state(), proceed::get);
    }
}
