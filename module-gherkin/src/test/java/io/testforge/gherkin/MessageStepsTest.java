package io.testforge.gherkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.gherkin.kafka.KafkaSteps;
import io.testforge.gherkin.rabbitmq.RabbitSteps;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaProbe;
import io.testforge.kafka.KafkaPublisher;
import io.testforge.rabbitmq.RabbitMessage;
import io.testforge.rabbitmq.RabbitMessageBuffer;
import io.testforge.rabbitmq.RabbitProbe;
import io.testforge.rabbitmq.RabbitPublisher;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** The message steps on buffers filled by hand: the waits are the probes', the steps only wire them. */
class MessageStepsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Waiter waiter = new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20)));
    private final GherkinProperties properties = new GherkinProperties(null, null, null, Duration.ofMillis(100));
    private final ScenarioVariables variables = new ScenarioVariables();

    @AfterEach
    void clear() {
        ScenarioContext.clear();
    }

    @Test
    void kafkaStepsAwaitByJsonPathRememberAFieldAndCatchADuplicate() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        KafkaSteps steps = new KafkaSteps(new KafkaProbe(buffer, waiter, mapper),
                new StaticListableBeanFactory().getBeanProvider(KafkaPublisher.class), variables, mapper, properties);
        variables.remember("orderId", "o-1");
        buffer.append(new KafkaMessage("orders.events", 0, 7, "o-1",
                "{\"orderId\":\"o-1\",\"paymentId\":\"p-9\"}", Instant.EPOCH, Map.of()));

        steps.exactlyOne("orders.events", "$.orderId", "${orderId}");
        steps.remember("$.paymentId", "paymentId");
        assertThat(variables.get("paymentId")).isEqualTo("p-9");
        steps.none("orders.events", "$.orderId", "o-2");

        buffer.append(new KafkaMessage("orders.events", 0, 8, "o-1", "{\"orderId\":\"o-1\"}", Instant.EPOCH, Map.of()));
        assertThatThrownBy(() -> steps.exactlyOne("orders.events", "$.orderId", "${orderId}"))
                .hasMessageContaining("Expected exactly one Kafka message")
                .hasMessageContaining("orders.events/0@8 key=o-1, orders.events/0@7 key=o-1");
        assertThatThrownBy(() -> steps.remember("$.refundId", "refundId"))
                .hasMessage("orders.events/0@7: the body has no field $.refundId; top-level fields: [orderId, paymentId]");
    }

    @Test
    void withScopeMessagesAnotherScenariosMessageNoLongerCounts() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(new KafkaMessage("orders.events", 0, 1, "o-9", "{\"status\":\"PAID\"}", Instant.EPOCH,
                Map.of("X-Test-Scope", "someone-else")));
        ScenarioContext.put(io.testforge.core.context.ScenarioKeys.TEST_SCOPE, "bdd-mine");
        KafkaPublisher publisher = new KafkaPublisher(null, mapper,
                new io.testforge.kafka.KafkaProbeProperties.PublishProperties("X-Test-Scope", null, null, null));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("publisher", publisher);

        // without it, the other scenario's PAID event satisfies the step
        new KafkaSteps(new KafkaProbe(buffer, waiter, mapper), beans.getBeanProvider(KafkaPublisher.class), variables,
                mapper, properties).arrives("orders.events", "$.status", "PAID");

        KafkaSteps scoped = new KafkaSteps(new KafkaProbe(buffer, waiter, mapper),
                beans.getBeanProvider(KafkaPublisher.class), variables, mapper,
                new GherkinProperties(null, null, null, Duration.ofMillis(100), true));
        assertThatThrownBy(() -> scoped.arrives("orders.events", "$.status", "PAID"))
                .isInstanceOf(ConditionTimeoutException.class).hasMessageContaining("failed: header X-Test-Scope");

        KafkaSteps unconfigured = new KafkaSteps(new KafkaProbe(buffer, waiter, mapper),
                new StaticListableBeanFactory().getBeanProvider(KafkaPublisher.class), variables, mapper,
                new GherkinProperties(null, null, null, Duration.ofMillis(100), true));
        assertThatThrownBy(() -> unconfigured.arrives("orders.events", "$.status", "PAID"))
                .hasMessageContaining("forge.gherkin.scope-messages=true needs forge.kafka.publish.scope-header");
    }

    @Test
    void kafkaPublishingWithoutABrokerSaysWhatIsMissing() {
        KafkaSteps steps = new KafkaSteps(new KafkaProbe(new KafkaMessageBuffer(1), waiter, mapper),
                new StaticListableBeanFactory().getBeanProvider(KafkaPublisher.class), variables, mapper, properties);

        assertThatThrownBy(() -> steps.publish("t", "k", "{}"))
                .hasMessageContaining("spring.kafka.bootstrap-servers");
        assertThatThrownBy(() -> steps.remember("$.a", "a"))
                .hasMessageContaining("No Kafka message was awaited in this scenario yet");
    }

    @Test
    void rabbitStepsAwaitByRoutingKeyAndTimeOutWithTheClosestMessages() {
        RabbitMessageBuffer buffer = new RabbitMessageBuffer(10);
        RabbitSteps steps = new RabbitSteps(new RabbitProbe(buffer, waiter, mapper),
                new StaticListableBeanFactory().getBeanProvider(RabbitPublisher.class), variables, mapper, properties);
        buffer.append(new RabbitMessage("payments", "payment.captured", "{\"orderId\":\"o-1\"}", Instant.EPOCH,
                Map.of(), "m-1", 3));

        steps.arrivesWithRoutingKey("payments", "payment.captured");
        steps.remember("$.orderId", "paid");
        assertThat(variables.get("paid")).isEqualTo("o-1");

        assertThatThrownBy(() -> steps.arrives("payments", "$.orderId", "o-2"))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("payments/payment.captured#3 key=payment.captured — failed: $.orderId");
    }
}
