package io.testforge.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.test;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import io.testforge.core.context.ScenarioContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;

class KafkaDiagnosticsExtensionTest {

    @BeforeEach
    void reset() {
        KafkaDiagnosticsTestkitFixture.WRITTEN.clear();
    }

    @AfterEach
    void clearScenario() {
        ScenarioContext.clear();
    }

    private static void run(String method) {
        EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(KafkaDiagnosticsTestkitFixture.class, method))
                .execute()
                .testEvents()
                .assertThatEvents()
                .haveExactly("failsAfterPublishingAndReceiving".equals(method) ? 1 : 0,
                        event(test(), finishedWithFailure(message(m -> m.contains("the order status")))));
    }

    @Test
    void aFailedTestPublishesItsOwnTrafficWithoutPayloads() {
        run("failsAfterPublishingAndReceiving");

        assertThat(KafkaDiagnosticsTestkitFixture.WRITTEN).singleElement().satisfies(written -> {
            assertThat(written).startsWith(
                    "module-kafka/KafkaDiagnosticsTestkitFixture-failsAfterPublishingAndReceiving.md\n");
            assertThat(written)
                    .contains("Failure: [the order status]")
                    .contains("## Published by the test (1)")
                    .contains("`orders.commands/0@0` key=o-1")
                    .contains("headers=[X-Test-Scope]")
                    .contains("## Received while it ran (1; only messages with X-Test-Scope=scope-mine)")
                    .contains("`orders.events/0@3` key=o-1")
                    .as("another scenario's message").doesNotContain("key=o-9")
                    .as("no payloads by default").doesNotContain("4111").doesNotContain("REJECTED");
        });
    }

    @Test
    void aPassingTestPublishesNothing() {
        run("passes");

        assertThat(KafkaDiagnosticsTestkitFixture.WRITTEN).isEmpty();
    }

    @Test
    void payloadsAppearOnlyWhenAskedForAndAreShortened() {
        KafkaMessage message = new KafkaMessage("t", 0, 1, "k", "x".repeat(600), Instant.EPOCH, Map.of());

        String withPayloads = KafkaDiagnosticsExtension.render("t", new AssertionError("boom"), List.of(message),
                List.of(), "every message", true);
        String without = KafkaDiagnosticsExtension.render("t", new AssertionError("boom"), List.of(message),
                List.of(), "every message", false);

        assertThat(withPayloads).contains("x".repeat(500) + "…").doesNotContain("x".repeat(501));
        assertThat(without).doesNotContain("xxx");
    }

    @Test
    void theBufferWatermarkSeesOnlyLaterMessages() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(new KafkaMessage("a", 0, 1, "before", "", null, Map.of()));
        long watermark = buffer.sequence();
        buffer.append(new KafkaMessage("b", 0, 1, "after-1", "", null, Map.of()));
        buffer.append(new KafkaMessage("a", 0, 2, "after-2", "", null, Map.of()));

        assertThat(buffer.since(watermark)).extracting(KafkaMessage::key).containsExactly("after-1", "after-2");
    }
}
