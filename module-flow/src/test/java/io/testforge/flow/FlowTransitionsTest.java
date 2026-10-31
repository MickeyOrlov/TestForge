package io.testforge.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.artifact.ArtifactSink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

class FlowTransitionsTest {

    enum Order { NEW, PAID, SHIPPED }

    /** A clock the steps move forward, so durations are exact. */
    static final class StepClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T10:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static FlowStep<Order> step(Order state, Order next, Runnable work) {
        return new FlowStep<>() {
            @Override
            public Order state() {
                return state;
            }

            @Override
            public Order execute(FlowContext context) {
                work.run();
                return next;
            }
        };
    }

    @Test
    void eachTransitionRecordsHowLongItsStepRan() {
        StepClock clock = new StepClock();
        FlowRunner<Order> runner = new FlowRunnerFactory(new FlowProperties(null, 10, 3), clock, ArtifactSink.NO_OP)
                .create(List.of(
                        step(Order.NEW, Order.PAID, () -> clock.advance(Duration.ofMillis(120))),
                        step(Order.PAID, Order.SHIPPED, () -> clock.advance(Duration.ofSeconds(2)))), List.of());

        FlowResult<Order> result = runner.run(Order.NEW, Order.SHIPPED);

        assertThat(result.transitions()).containsExactly(
                new FlowTransition<>(Order.NEW, Order.PAID, Duration.ofMillis(120)),
                new FlowTransition<>(Order.PAID, Order.SHIPPED, Duration.ofSeconds(2)));
        assertThat(result.duration()).isEqualTo(Duration.ofMillis(2120));
    }

    @Test
    void aTimeoutNamesTheSlowestStep() {
        StepClock clock = new StepClock();
        FlowRunner<Order> runner = new FlowRunnerFactory(new FlowProperties(Duration.ofSeconds(5), 10, 3), clock,
                ArtifactSink.NO_OP).create(List.of(
                        step(Order.NEW, Order.PAID, () -> clock.advance(Duration.ofSeconds(6)))), List.of());

        assertThatThrownBy(() -> runner.run(Order.NEW, Order.SHIPPED))
                .isInstanceOf(FlowException.class)
                .hasMessageContaining("Flow timeout after PT5S")
                .hasMessageContaining("slowest step: NEW -> PAID (6000 ms)");
    }

    @Test
    void aFailedStepSaysHowLongItRanBeforeFailing() {
        StepClock clock = new StepClock();
        FlowRunner<Order> runner = new FlowRunnerFactory(new FlowProperties(null, 10, 3), clock, ArtifactSink.NO_OP)
                .create(List.of(step(Order.NEW, Order.PAID, () -> {
                    clock.advance(Duration.ofMillis(900));
                    throw new IllegalStateException("payment API 503");
                })), List.of());

        assertThatThrownBy(() -> runner.run(Order.NEW, Order.SHIPPED))
                .hasMessageContaining("Flow step failed at state: NEW after 900 ms");
    }
}
