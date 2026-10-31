package io.testforge.flow;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

public interface FlowStep<S> {

    S state();

    S execute(FlowContext context);

    /**
     * The states this step may return. Empty — the default — declares nothing
     * and checks nothing. Once declared, a step that returns anything else
     * fails the run at once, and the runner can draw and verify the flow
     * before running it ({@link FlowRunner#describe()}, {@link FlowRunner#verify}). Named
     * {@code declaredNext}, not {@code next}, so a {@code record Step(S state, S next)}
     * implementing this interface keeps compiling.
     */
    default Set<S> declaredNext() {
        return Set.of();
    }

    /**
     * A step with its transitions declared:
     *
     * <pre>{@code
     * FlowStep.of(SUBMITTED, ctx -> ctx.get("amount", Integer.class) > 1000 ? MANAGER_REVIEW : OPERATOR_REVIEW,
     *         MANAGER_REVIEW, OPERATOR_REVIEW)
     * }</pre>
     */
    @SafeVarargs
    static <S> FlowStep<S> of(S state, Function<FlowContext, S> action, S... next) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(action, "action");
        Set<S> declared = Set.of(next);
        return new FlowStep<>() {
            @Override
            public S state() {
                return state;
            }

            @Override
            public S execute(FlowContext context) {
                return action.apply(context);
            }

            @Override
            public Set<S> declaredNext() {
                return declared;
            }

            @Override
            public String toString() {
                return "FlowStep[" + state + " -> " + declared + "]";
            }
        };
    }
}
