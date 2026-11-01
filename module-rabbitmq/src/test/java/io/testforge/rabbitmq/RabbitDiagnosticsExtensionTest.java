package io.testforge.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import io.testforge.core.context.ScenarioContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class RabbitDiagnosticsExtensionTest {

    @BeforeEach
    void reset() {
        RabbitDiagnosticsTestkitFixture.WRITTEN.clear();
    }

    @AfterEach
    void clearScenario() {
        ScenarioContext.clear();
    }

    private static void run(String method, int failures) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(RabbitDiagnosticsTestkitFixture.class, method))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly(failures, event(test(), finishedWithFailure(message(m -> m.contains("payment status")))));
    }

    @Test
    void aFailedTestPublishesItsOwnTrafficInTheSameReportAsKafka() {
        run("failsAfterPublishingAndReceiving", 1);

        assertThat(RabbitDiagnosticsTestkitFixture.WRITTEN).singleElement().satisfies(written -> assertThat(written)
                .startsWith("module-rabbitmq/RabbitDiagnosticsTestkitFixture-failsAfterPublishingAndReceiving.md\n")
                .contains("# RabbitMQ traffic before `failsAfterPublishingAndReceiving()` failed")
                .contains("Failure: [the payment status]")
                .contains("## Published by the test (1)")
                .contains("`orders/order.pay (published)` key=order.pay")
                .contains("headers=[X-Test-Scope]")
                .contains("## Received while it ran (1; only messages with X-Test-Scope=scope-mine)")
                .contains("`payments/payment.rejected#7` key=payment.rejected")
                .as("another scenario's message").doesNotContain("payment.captured")
                .as("no bodies by default").doesNotContain("4111").doesNotContain("REJECTED\""));
    }

    @Test
    void aPassingTestPublishesNothing() {
        run("passes", 0);

        assertThat(RabbitDiagnosticsTestkitFixture.WRITTEN).isEmpty();
    }
}
