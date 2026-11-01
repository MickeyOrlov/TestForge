package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.rabbitmq.RabbitCollector;
import io.testforge.rabbitmq.RabbitDiagnosticsExtension;
import io.testforge.rabbitmq.RabbitMessage;
import io.testforge.rabbitmq.RabbitMessageBuffer;
import io.testforge.rabbitmq.RabbitMessageFilter;
import io.testforge.rabbitmq.RabbitProbe;
import io.testforge.rabbitmq.RabbitProbeProperties;
import io.testforge.rabbitmq.RabbitPublisher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
// Spring Boot 3.5's @ServiceConnection knows this class, not org.testcontainers.rabbitmq's
import org.testcontainers.containers.RabbitMQContainer;

/**
 * An event-driven flow on RabbitMQ, the way a test sees it: publish the
 * command with the scenario's scope, wait for the event the service emits,
 * prove nothing was sent twice or sent at all. The "service" here is a queue
 * of its own bound to the same exchange — the collector must never take its
 * messages.
 *
 * <p>Tagged {@code containers} and excluded from the default build — run with
 * {@code ./gradlew :example-tests:containersTest} when Docker is available.
 */
@SpringBootTest(properties = {
        "forge.rabbitmq.enabled=true",
        "forge.rabbitmq.bindings[0].exchange=orders",
        "forge.rabbitmq.publish.scope-header=X-Test-Scope"
})
@Tag("containers")
@Testcontainers
// a failed test leaves module-rabbitmq/<test>.md in the run index: what it published and received
@ExtendWith(RabbitDiagnosticsExtension.class)
class RabbitMqProbeIT {

    static final String SERVICE_QUEUE = "order-service";

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-alpine");

    @Autowired
    RabbitProbe probe;

    @Autowired
    RabbitPublisher publisher;

    @Autowired
    RabbitCollector collector;

    /** What the system's own deployment declares; the collector binds to it at context start. */
    @BeforeAll
    static void topology() throws Exception {
        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare("orders", "topic", true);
            channel.exchangeDeclare("audit", "topic", true);
            channel.queueDeclare(SERVICE_QUEUE, true, false, false, null);
            channel.queueBind(SERVICE_QUEUE, "orders", "order.*");
        }
    }

    @AfterEach
    void clearScope() {
        ScenarioContext.clear();
    }

    @Test
    void aPublishedMessageIsAwaitedByRoutingKeyAndScope() {
        String scope = "it-" + UUID.randomUUID();
        ScenarioContext.put(ScenarioKeys.TEST_SCOPE, scope);
        String orderId = UUID.randomUUID().toString();

        publisher.publish("orders", "order.placed", Map.of("orderId", orderId));

        RabbitMessage received = probe.awaitMessage(RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.placed")
                .headerEquals("X-Test-Scope", scope).jsonPathEquals("$.orderId", orderId).build());
        assertThat(received.position()).startsWith("orders/order.placed#");
        assertThat(received.messageId()).isNotBlank();
    }

    @Test
    void theCollectorObservesWithoutTakingTheServicesMessages() throws Exception {
        String orderId = UUID.randomUUID().toString();
        drain();

        publisher.publish("orders", "order.paid", Map.of("orderId", orderId));
        probe.awaitMessage(RabbitMessageFilter.builder().exchange("orders").jsonPathEquals("$.orderId", orderId)
                .build());

        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            GetResponse delivered = channel.basicGet(SERVICE_QUEUE, true);
            assertThat(delivered).as("the service's queue still has its copy").isNotNull();
            assertThat(new String(delivered.getBody(), StandardCharsets.UTF_8)).contains(orderId);
        }
        assertThat(collector.queue()).startsWith("amq.gen-");
    }

    @Test
    void aDuplicateAndAMessageThatMustNotBeSentAreCaught() {
        String orderId = UUID.randomUUID().toString();
        RabbitMessageFilter paid = RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.paid").jsonPathEquals("$.orderId", orderId).build();

        // the bug: a retrying service emits the event twice
        publisher.publish("orders", "order.paid", Map.of("orderId", orderId));
        publisher.publish("orders", "order.paid", Map.of("orderId", orderId));

        assertThatThrownBy(() -> probe.awaitExactlyOne(paid, Duration.ofMillis(500)))
                .hasMessageContaining("Expected exactly one RabbitMQ message");
        probe.assertNoMessage(RabbitMessageFilter.builder()
                .exchange("orders").routingKey("order.refunded").jsonPathEquals("$.orderId", orderId).build(),
                Duration.ofMillis(500));
    }

    @Test
    void aMessageNoQueueIsBoundForFailsWhereItWasPublished() {
        assertThatThrownBy(() -> publisher.publish("audit", "nobody.listens", Map.of("x", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Publishing to exchange audit with routing key nobody.listens was returned")
                .hasMessageContaining("NO_ROUTE");
    }

    @Test
    void aBindingToAMissingExchangeStopsTheCollectorAndSaysWhy() {
        CachingConnectionFactory factory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        RabbitMessageBuffer buffer = new RabbitMessageBuffer(10);
        RabbitCollector broken = new RabbitCollector(factory, new RabbitProbeProperties(true,
                List.of(new RabbitProbeProperties.Binding("no-such-exchange", null)), 0, null), buffer);
        try {
            broken.start();

            assertThat(broken.isRunning()).isFalse();
            assertThat(buffer.collectorFailure()).get().asString()
                    .contains("binding no-such-exchange / # failed")
                    .contains("NOT_FOUND");
        } finally {
            broken.stop();
            factory.destroy();
        }
    }

    private static void drain() throws Exception {
        try (Connection connection = connection(); Channel channel = connection.createChannel()) {
            channel.queuePurge(SERVICE_QUEUE);
        }
    }

    private static Connection connection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(rabbit.getHost());
        factory.setPort(rabbit.getAmqpPort());
        factory.setUsername(rabbit.getAdminUsername());
        factory.setPassword(rabbit.getAdminPassword());
        return factory.newConnection();
    }
}
