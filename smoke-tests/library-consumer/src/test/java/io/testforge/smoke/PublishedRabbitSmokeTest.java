package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.rabbitmq.RabbitMessage;
import io.testforge.rabbitmq.RabbitMessageBuffer;
import io.testforge.rabbitmq.RabbitMessageFilter;
import io.testforge.rabbitmq.RabbitProbe;
import io.testforge.rabbitmq.RabbitPublisher;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * module-rabbitmq from published jars: spring-rabbit arrives through its POM,
 * Spring Boot's RabbitAutoConfiguration makes the connection factory, and
 * nothing connects while the collector is off.
 */
@SpringBootTest(classes = SmokeTestApplication.class)
class PublishedRabbitSmokeTest {

    @Autowired
    RabbitProbe probe;

    @Autowired
    RabbitPublisher publisher;

    @Autowired
    RabbitMessageBuffer buffer;

    @Test
    void theProbeAndPublisherAreWiredWithoutABroker() {
        buffer.append(new RabbitMessage("orders", "order.paid", "{\"orderId\":\"o-1\"}", Instant.now(), Map.of(),
                "m-1", 1));

        assertThat(probe.awaitMessage(RabbitMessageFilter.builder()
                .exchange("orders").jsonPathEquals("$.orderId", "o-1").build()).position())
                .isEqualTo("orders/order.paid#1");
        assertThat(publisher.publishedByThisThread()).isEmpty();
    }
}
