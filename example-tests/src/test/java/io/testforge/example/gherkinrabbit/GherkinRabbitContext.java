package io.testforge.example.gherkinrabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
// Spring Boot 3.5's @ServiceConnection knows this class, not org.testcontainers.rabbitmq's
import org.testcontainers.containers.RabbitMQContainer;

/**
 * The Spring context of the RabbitMQ feature: a broker in a container, the
 * topology the deployment would declare, and {@link PaymentService} as the
 * system under test. The container starts here because Cucumber, not JUnit
 * Jupiter, runs the scenarios.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {
        "forge.rabbitmq.enabled=true",
        "forge.rabbitmq.bindings[0].exchange=payments"
})
public class GherkinRabbitContext {

    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-alpine");

    static {
        RABBIT.start();
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(RABBIT.getAdminUsername());
        factory.setPassword(RABBIT.getAdminPassword());
        try (Connection connection = factory.newConnection(); Channel channel = connection.createChannel()) {
            channel.exchangeDeclare("orders", "topic", true);
            channel.exchangeDeclare("payments", "topic", true);
            channel.queueDeclare("payment-service", true, false, false, null);
            channel.queueBind("payment-service", "orders", "order.pay");
        } catch (Exception e) {
            throw new IllegalStateException("Could not declare the example topology", e);
        }
    }

    @TestConfiguration
    static class PaymentFlow {

        @Bean
        PaymentService paymentService(RabbitTemplate rabbit, ObjectMapper mapper) {
            return new PaymentService(rabbit, mapper);
        }
    }

    /** An order.pay command on "orders" becomes one payment.captured event on "payments". */
    static class PaymentService {

        private final RabbitTemplate rabbit;
        private final ObjectMapper mapper;

        PaymentService(RabbitTemplate rabbit, ObjectMapper mapper) {
            this.rabbit = rabbit;
            this.mapper = mapper;
        }

        @RabbitListener(queues = "payment-service")
        void onCommand(String command) throws Exception {
            JsonNode body = mapper.readTree(command);
            rabbit.convertAndSend("payments", "payment.captured",
                    "{\"orderId\":\"" + body.path("orderId").asText() + "\",\"status\":\"CAPTURED\"}");
        }
    }
}
