package io.testforge.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.impl.LongStringHelper;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;

class RabbitProbeUnitTest {

    private final RabbitMessageBuffer buffer = new RabbitMessageBuffer(10);
    private final RabbitProbe probe = new RabbitProbe(buffer,
            new Waiter(new WaitProperties(Duration.ofMillis(300), Duration.ofMillis(20))), new ObjectMapper());
    private final RabbitCollector collector = new RabbitCollector(null,
            new RabbitProbeProperties(true, null, 0, null), buffer);

    private RabbitMessage delivered(String exchange, String routingKey, String body, Map<String, Object> headers) {
        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                .headers(headers).messageId("m-1").timestamp(new Date(0)).build();
        RabbitMessage message = collector.received(new Envelope(1, false, exchange, routingKey), props,
                body.getBytes(StandardCharsets.UTF_8));
        buffer.append(message);
        return message;
    }

    @Test
    void aMessageIsFoundByExchangeRoutingKeyHeaderAndJsonPath() {
        delivered("orders", "order.placed", "{\"orderId\":\"o-1\"}", Map.of("X-Test-Scope", "s-1"));
        delivered("orders", "order.paid", "{\"orderId\":\"o-1\"}", Map.of("X-Test-Scope", "s-1"));

        RabbitMessage paid = probe.awaitMessage(RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.paid")
                .headerEquals("X-Test-Scope", "s-1").jsonPathEquals("$.orderId", "o-1").build());

        assertThat(paid.position()).isEqualTo("orders/order.paid#2");
        assertThat(paid.messageId()).isEqualTo("m-1");
    }

    @Test
    void aTimeoutSpeaksInRoutingKeysAndNeverPrintsTheBody() {
        delivered("orders", "order.placed", "{\"card\":\"4111\"}", Map.of());

        assertThatThrownBy(() -> probe.awaitMessage(RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.paid").build()))
                .isInstanceOf(ConditionTimeoutException.class)
                .hasMessageContaining("No RabbitMQ message matching")
                .hasMessageContaining("orders/order.placed#1 key=order.placed — failed: routing key")
                .hasMessageNotContaining("4111");
        assertThatThrownBy(() -> probe.awaitMessage(RabbitMessageFilter.builder().exchange("refunds").build()))
                .hasMessageContaining("Exchange refunds had no messages in the buffer — is it in "
                        + "forge.rabbitmq.bindings");
    }

    @Test
    void aRedeliveredDuplicateIsCaught() {
        delivered("orders", "order.paid", "{}", Map.of());
        delivered("orders", "order.paid", "{}", Map.of());

        assertThatThrownBy(() -> probe.awaitExactlyOne(RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.paid").build(), Duration.ofMillis(100)))
                .hasMessageContaining("Expected exactly one RabbitMQ message")
                .hasMessageContaining("2: orders/order.paid#2 key=order.paid, orders/order.paid#1 key=order.paid");
    }

    @Test
    void amqpHeaderValuesAreReadAsText() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("scope", LongStringHelper.asLongString("s-1"));
        raw.put("attempt", 3);
        raw.put("raw", "b".getBytes(StandardCharsets.UTF_8));
        raw.put("none", null);

        assertThat(RabbitCollector.headers(raw))
                .containsEntry("scope", "s-1").containsEntry("attempt", "3").containsEntry("raw", "b")
                .containsEntry("none", null);
    }

    @Test
    void aJsonStringIsLabelledJsonForASpringConsumersConverter() {
        assertThat(RabbitPublisher.contentType(Map.of("a", 1), "{\"a\":1}")).isEqualTo("application/json");
        assertThat(RabbitPublisher.contentType(" {\"a\":1} ", " {\"a\":1} ")).isEqualTo("application/json");
        assertThat(RabbitPublisher.contentType("[1,2]", "[1,2]")).isEqualTo("application/json");
        assertThat(RabbitPublisher.contentType("PAY o-1", "PAY o-1")).isEqualTo("text/plain");
        assertThat(RabbitPublisher.contentType(null, null)).isEqualTo("text/plain");
    }

    @Test
    void bindingsDefaultToEveryRoutingKeyAndBlankOnesAreDropped() {
        RabbitProbeProperties properties = new RabbitProbeProperties(true, java.util.Arrays.asList(
                new RabbitProbeProperties.Binding("orders", null),
                new RabbitProbeProperties.Binding(" ", "x"),
                null), 0, null);

        assertThat(properties.bindings()).containsExactly(new RabbitProbeProperties.Binding("orders", "#"));
        assertThat(properties.maxMessagesPerExchange()).isEqualTo(1_000);
        assertThat(properties.publish().confirmTimeout()).isEqualTo(Duration.ofSeconds(10));
    }
}
