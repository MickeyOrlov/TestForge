package io.testforge.gherkin.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.testforge.gherkin.GherkinProperties;
import io.testforge.gherkin.JsonFields;
import io.testforge.gherkin.ScenarioVariables;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageFilter;
import io.testforge.kafka.KafkaProbe;
import io.testforge.kafka.KafkaPublisher;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Kafka steps over {@link KafkaProbe} and {@link KafkaPublisher}: the waits,
 * duplicate and absence checks and failure messages are module-kafka's own.
 *
 * <pre>
 * When I publish a Kafka message to "orders.commands" with key "${orderId}":
 *   """
 *   {"type": "PAY", "orderId": "${orderId}"}
 *   """
 * Then exactly one Kafka message arrives on "orders.events" where "$.orderId" is "${orderId}"
 * And I remember the Kafka message field "$.paymentId" as "paymentId"
 * </pre>
 *
 * "Exactly one" and "no message" watch for {@code forge.gherkin.settle}.
 * Topics must be in {@code forge.kafka.topics} for the collector to see them.
 */
public class KafkaSteps {

    private final KafkaProbe probe;
    private final ObjectProvider<KafkaPublisher> publisher;
    private final ScenarioVariables variables;
    private final ObjectMapper mapper;
    private final GherkinProperties properties;
    private KafkaMessage last;

    public KafkaSteps(KafkaProbe probe, ObjectProvider<KafkaPublisher> publisher, ScenarioVariables variables,
                      ObjectMapper mapper, GherkinProperties properties) {
        this.probe = probe;
        this.publisher = publisher;
        this.variables = variables;
        this.mapper = mapper;
        this.properties = properties;
    }

    @When("I publish a Kafka message to {string} with key {string}:")
    public void publish(String topic, String key, String body) {
        KafkaPublisher kafka = publisher.getIfAvailable();
        if (kafka == null) {
            throw new IllegalStateException("No KafkaPublisher: spring.kafka.bootstrap-servers must point at a broker "
                    + "for Spring Boot to create the KafkaTemplate it uses");
        }
        kafka.publish(variables.resolve(topic), variables.resolve(key), variables.resolve(body));
    }

    @Then("a Kafka message arrives on {string} with key {string}")
    public void arrivesWithKey(String topic, String key) {
        last = probe.awaitMessage(KafkaMessageFilter.builder().topic(variables.resolve(topic))
                .key(variables.resolve(key)).build());
    }

    @Then("a Kafka message arrives on {string} where {string} is {string}")
    public void arrives(String topic, String path, String value) {
        last = probe.awaitMessage(where(topic, path, value));
    }

    @Then("exactly one Kafka message arrives on {string} where {string} is {string}")
    public void exactlyOne(String topic, String path, String value) {
        last = probe.awaitExactlyOne(where(topic, path, value), properties.settle());
    }

    @Then("no Kafka message arrives on {string} where {string} is {string}")
    public void none(String topic, String path, String value) {
        probe.assertNoMessage(where(topic, path, value), properties.settle());
    }

    @Then("I remember the Kafka message field {string} as {string}")
    public void remember(String path, String name) {
        if (last == null) {
            throw new IllegalStateException("No Kafka message was awaited in this scenario yet: wait for one with "
                    + "'a Kafka message arrives on \"<topic>\" …' first");
        }
        variables.remember(name, JsonFields.read(last.value(), path, last.position(), mapper));
    }

    private KafkaMessageFilter where(String topic, String path, String value) {
        return KafkaMessageFilter.builder().topic(variables.resolve(topic))
                .jsonPathEquals(path, variables.resolve(value)).build();
    }
}
