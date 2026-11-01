package io.testforge.gherkin.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.testforge.gherkin.GherkinProperties;
import io.testforge.gherkin.JsonFields;
import io.testforge.gherkin.MessageScope;
import io.testforge.gherkin.ScenarioVariables;
import io.testforge.rabbitmq.RabbitMessage;
import io.testforge.rabbitmq.RabbitMessageFilter;
import io.testforge.rabbitmq.RabbitProbe;
import io.testforge.rabbitmq.RabbitPublisher;
import org.springframework.beans.factory.ObjectProvider;

/**
 * RabbitMQ steps over {@link RabbitProbe} and {@link RabbitPublisher}: the
 * waits, duplicate and absence checks, the confirmed publish and the failure
 * messages are module-rabbitmq's own.
 *
 * <pre>
 * When I publish a RabbitMQ message to exchange "orders" with routing key "order.pay":
 *   """
 *   {"orderId": "${orderId}"}
 *   """
 * Then exactly one RabbitMQ message arrives on exchange "payments" where "$.orderId" is "${orderId}"
 * </pre>
 *
 * "Exactly one" and "no message" watch for {@code forge.gherkin.settle}.
 * Exchanges must be in {@code forge.rabbitmq.bindings} for the collector to see them.
 */
public class RabbitSteps {

    private final RabbitProbe probe;
    private final ObjectProvider<RabbitPublisher> publisher;
    private final ScenarioVariables variables;
    private final ObjectMapper mapper;
    private final GherkinProperties properties;
    private RabbitMessage last;

    public RabbitSteps(RabbitProbe probe, ObjectProvider<RabbitPublisher> publisher, ScenarioVariables variables,
                       ObjectMapper mapper, GherkinProperties properties) {
        this.probe = probe;
        this.publisher = publisher;
        this.variables = variables;
        this.mapper = mapper;
        this.properties = properties;
    }

    @When("I publish a RabbitMQ message to exchange {string} with routing key {string}:")
    public void publish(String exchange, String routingKey, String body) {
        RabbitPublisher rabbit = publisher.getIfAvailable();
        if (rabbit == null) {
            throw new IllegalStateException("No RabbitPublisher: Spring Boot creates the ConnectionFactory it uses "
                    + "when spring-rabbit is on the classpath");
        }
        rabbit.publish(variables.resolve(exchange), variables.resolve(routingKey), variables.resolve(body));
    }

    @Then("a RabbitMQ message arrives on exchange {string} with routing key {string}")
    public void arrivesWithRoutingKey(String exchange, String routingKey) {
        last = probe.awaitMessage(scoped(RabbitMessageFilter.builder().exchange(variables.resolve(exchange))
                .routingKey(variables.resolve(routingKey))).build());
    }

    @Then("a RabbitMQ message arrives on exchange {string} where {string} is {string}")
    public void arrives(String exchange, String path, String value) {
        last = probe.awaitMessage(where(exchange, path, value));
    }

    @Then("exactly one RabbitMQ message arrives on exchange {string} where {string} is {string}")
    public void exactlyOne(String exchange, String path, String value) {
        last = probe.awaitExactlyOne(where(exchange, path, value), properties.settle());
    }

    @Then("no RabbitMQ message arrives on exchange {string} where {string} is {string}")
    public void none(String exchange, String path, String value) {
        probe.assertNoMessage(where(exchange, path, value), properties.settle());
    }

    @Then("I remember the RabbitMQ message field {string} as {string}")
    public void remember(String path, String name) {
        if (last == null) {
            throw new IllegalStateException("No RabbitMQ message was awaited in this scenario yet: wait for one with "
                    + "'a RabbitMQ message arrives on exchange \"<exchange>\" …' first");
        }
        variables.remember(name, JsonFields.read(last.value(), path, last.position(), mapper));
    }

    private RabbitMessageFilter where(String exchange, String path, String value) {
        return scoped(RabbitMessageFilter.builder().exchange(variables.resolve(exchange))
                .jsonPathEquals(path, variables.resolve(value))).build();
    }

    /** With forge.gherkin.scope-messages, only messages carrying this scenario's scope header. */
    private RabbitMessageFilter.Builder scoped(RabbitMessageFilter.Builder filter) {
        RabbitPublisher rabbit = publisher.getIfAvailable();
        MessageScope.of(properties, rabbit == null ? null : rabbit.scopeHeader(), "forge.rabbitmq.publish.scope-header")
                .ifPresent(header -> filter.headerEquals(header.name(), header.value()));
        return filter;
    }
}
