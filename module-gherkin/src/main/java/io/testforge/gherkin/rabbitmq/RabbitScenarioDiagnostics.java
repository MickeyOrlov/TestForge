package io.testforge.gherkin.rabbitmq;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.core.messaging.MessageTrafficReport;
import io.testforge.gherkin.ScenarioFileNames;
import io.testforge.rabbitmq.RabbitMessage;
import io.testforge.rabbitmq.RabbitMessageBuffer;
import io.testforge.rabbitmq.RabbitProbeProperties;
import io.testforge.rabbitmq.RabbitPublisher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What {@code RabbitDiagnosticsExtension} does for a JUnit test, for a
 * scenario: when it fails, the RabbitMQ traffic it published and received —
 * scoped to its scope header — is attached to the scenario and written to
 * the run index under {@code module-gherkin}. Bodies only with
 * {@code forge.rabbitmq.diagnostics.payloads=true}.
 */
public class RabbitScenarioDiagnostics {

    private final RabbitMessageBuffer buffer;
    private final ObjectProvider<RabbitPublisher> publisher;
    private final RabbitProbeProperties properties;
    private final ObjectProvider<ArtifactSink> sink;
    private long watermark;

    public RabbitScenarioDiagnostics(RabbitMessageBuffer buffer, ObjectProvider<RabbitPublisher> publisher,
                                     RabbitProbeProperties properties, ObjectProvider<ArtifactSink> sink) {
        this.buffer = buffer;
        this.publisher = publisher;
        this.properties = properties;
        this.sink = sink;
    }

    @Before(order = 10)
    public void mark() {
        publisher.ifAvailable(RabbitPublisher::clearPublished);
        watermark = buffer.sequence();
    }

    /** After hooks run highest order first: this one still sees the scenario's scope. */
    @After(order = 200)
    public void report(Scenario scenario) {
        if (!scenario.isFailed()) {
            return;
        }
        Optional<RabbitPublisher> rabbit = Optional.ofNullable(publisher.getIfAvailable());
        String scopeHeader = rabbit.map(RabbitPublisher::scopeHeader).orElse(null);
        Optional<String> scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE);
        List<RabbitMessage> published = rabbit.map(RabbitPublisher::publishedByThisThread).orElse(List.of());
        List<RabbitMessage> received = MessageTrafficReport.ownMessages(buffer.since(watermark), scopeHeader, scope);
        if (published.isEmpty() && received.isEmpty()) {
            return;
        }
        int last = properties.diagnostics().last();
        String markdown = MessageTrafficReport.render("RabbitMQ", scenario.getName(),
                new AssertionError("see the failed step of " + scenario.getUri() + ":" + scenario.getLine()),
                MessageTrafficReport.tail(published, last), MessageTrafficReport.tail(received, last),
                MessageTrafficReport.scopeNote(scopeHeader, scope), properties.diagnostics().payloads());
        scenario.attach(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown", "rabbitmq-traffic.md");
        sink.getIfAvailable(() -> ArtifactSink.NO_OP).write("module-gherkin", "failed-scenario-rabbitmq",
                ScenarioFileNames.of(scenario.getName(), scenario.getLine(), "rabbitmq"), "text/markdown", markdown);
    }
}
