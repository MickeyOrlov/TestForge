package io.testforge.gherkin.kafka;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.core.messaging.MessageTrafficReport;
import io.testforge.gherkin.ScenarioFileNames;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaProbeProperties;
import io.testforge.kafka.KafkaPublisher;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;

/**
 * What {@code KafkaDiagnosticsExtension} does for a JUnit test, for a
 * scenario: when it fails, the Kafka traffic it published and received —
 * scoped to its scope header — is attached to the scenario and written to
 * the run index under {@code module-gherkin}. Bodies only with
 * {@code forge.kafka.diagnostics.payloads=true}.
 */
public class KafkaScenarioDiagnostics {

    private final KafkaMessageBuffer buffer;
    private final ObjectProvider<KafkaPublisher> publisher;
    private final KafkaProbeProperties properties;
    private final ObjectProvider<ArtifactSink> sink;
    private long watermark;

    public KafkaScenarioDiagnostics(KafkaMessageBuffer buffer, ObjectProvider<KafkaPublisher> publisher,
                                    KafkaProbeProperties properties, ObjectProvider<ArtifactSink> sink) {
        this.buffer = buffer;
        this.publisher = publisher;
        this.properties = properties;
        this.sink = sink;
    }

    @Before(order = 10)
    public void mark() {
        publisher.ifAvailable(KafkaPublisher::clearPublished);
        watermark = buffer.sequence();
    }

    /** After hooks run highest order first: this one still sees the scenario's scope. */
    @After(order = 200)
    public void report(Scenario scenario) {
        if (!scenario.isFailed()) {
            return;
        }
        Optional<KafkaPublisher> kafka = Optional.ofNullable(publisher.getIfAvailable());
        String scopeHeader = kafka.map(KafkaPublisher::scopeHeader).orElse(null);
        Optional<String> scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE);
        List<KafkaMessage> published = kafka.map(KafkaPublisher::publishedByThisThread).orElse(List.of());
        List<KafkaMessage> received = MessageTrafficReport.ownMessages(buffer.since(watermark), scopeHeader, scope);
        if (published.isEmpty() && received.isEmpty()) {
            return;
        }
        int last = properties.diagnostics().last();
        String markdown = MessageTrafficReport.render("Kafka", scenario.getName(),
                new AssertionError("see the failed step of " + scenario.getUri() + ":" + scenario.getLine()),
                MessageTrafficReport.tail(published, last), MessageTrafficReport.tail(received, last),
                MessageTrafficReport.scopeNote(scopeHeader, scope), properties.diagnostics().payloads());
        scenario.attach(markdown.getBytes(StandardCharsets.UTF_8), "text/markdown", "kafka-traffic.md");
        sink.getIfAvailable(() -> ArtifactSink.NO_OP).write("module-gherkin", "failed-scenario-kafka",
                ScenarioFileNames.of(scenario.getName(), scenario.getLine(), "kafka"), "text/markdown", markdown);
    }
}
