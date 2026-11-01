package io.testforge.example.gherkin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The one Spring context every scenario runs in — a project has exactly one
 * {@code @CucumberContextConfiguration} class in its glue. The WireMock server
 * stands in for the order service, the embedded broker and
 * {@link PaymentService} for the payment flow on Kafka.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = {
        "forge.http.scope.header=X-Test-Scope",
        "forge.kafka.enabled=true",
        "forge.kafka.topics[0]=orders.events",
        "forge.kafka.poll-timeout=200ms",
        // the payment service must see a command sent before its partitions were assigned
        "spring.kafka.consumer.auto-offset-reset=earliest"
})
@EmbeddedKafka(partitions = 1, topics = {"orders.commands", "orders.events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public class GherkinExampleContext {

    static final WireMockServer ORDER_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        ORDER_SERVICE.start();
    }

    @DynamicPropertySource
    static void orderService(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + ORDER_SERVICE.port());
        // the same WireMock is the shared mock server: stubs match the scenario's scope header
        registry.add("forge.mock.base-url", () -> "http://localhost:" + ORDER_SERVICE.port());
        registry.add("forge.mock.scope-header", () -> "X-Test-Scope");
    }

    /** The system under test, in miniature: a PAY command on Kafka becomes one PAID event. */
    @TestConfiguration
    static class PaymentFlow {

        @Bean
        PaymentService paymentService(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
            return new PaymentService(kafka, mapper);
        }
    }

    static class PaymentService {

        private final KafkaTemplate<String, String> kafka;
        private final ObjectMapper mapper;

        PaymentService(KafkaTemplate<String, String> kafka, ObjectMapper mapper) {
            this.kafka = kafka;
            this.mapper = mapper;
        }

        @KafkaListener(topics = "orders.commands", groupId = "payment-service")
        void onCommand(String command) throws Exception {
            JsonNode body = mapper.readTree(command);
            if ("PAY".equals(body.path("type").asText())) {
                String orderId = body.path("orderId").asText();
                kafka.send("orders.events", orderId, "{\"orderId\":\"" + orderId + "\",\"status\":\"PAID\"}");
            }
        }
    }
}
