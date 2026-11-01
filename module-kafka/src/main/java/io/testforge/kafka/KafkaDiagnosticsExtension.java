package io.testforge.kafka;

import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.core.messaging.MessageTrafficReport;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * When a test fails, publishes its Kafka traffic to the run index as
 * {@code module-kafka/<test>.md}: the events it published, then the messages
 * that arrived in the buffer while it ran — with the scenario's scope header
 * configured and set, only those carrying its scope.
 *
 * <pre>{@code
 * @SpringBootTest
 * @ExtendWith(KafkaDiagnosticsExtension.class)
 * class OrderEventsTest { ... }
 * }</pre>
 *
 * Positions, keys and header names by default; payloads only with
 * {@code forge.kafka.diagnostics.payloads=true}. The original failure is
 * rethrown unchanged; a diagnostics problem is attached to it as suppressed.
 */
public class KafkaDiagnosticsExtension implements BeforeEachCallback, TestExecutionExceptionHandler {

    static final String SOURCE = "module-kafka";
    static final int MAX_PAYLOAD_CHARS = MessageTrafficReport.MAX_PAYLOAD_CHARS;

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(KafkaDiagnosticsExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        spring(context).ifPresent(spring -> {
            bean(spring, KafkaPublisher.class).ifPresent(KafkaPublisher::clearPublished);
            bean(spring, KafkaMessageBuffer.class)
                    .ifPresent(buffer -> context.getStore(NAMESPACE).put("watermark", buffer.sequence()));
        });
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        try {
            spring(context).ifPresent(spring -> publish(context, spring, failure));
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
        throw failure;
    }

    private static void publish(ExtensionContext context, ApplicationContext spring, Throwable failure) {
        KafkaProbeProperties properties = bean(spring, KafkaProbeProperties.class).orElse(null);
        Optional<KafkaPublisher> publisher = bean(spring, KafkaPublisher.class);
        Optional<KafkaMessageBuffer> buffer = bean(spring, KafkaMessageBuffer.class);
        if (properties == null || (publisher.isEmpty() && buffer.isEmpty())) {
            return;
        }
        int last = properties.diagnostics().last();
        List<KafkaMessage> published = publisher.map(KafkaPublisher::publishedByThisThread).orElse(List.of());
        Long watermark = context.getStore(NAMESPACE).get("watermark", Long.class);
        String scopeHeader = publisher.map(KafkaPublisher::scopeHeader).orElse(null);
        Optional<String> scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE);
        List<KafkaMessage> received = MessageTrafficReport.ownMessages(buffer.filter(ignored -> watermark != null)
                .map(messages -> messages.since(watermark)).orElse(List.<KafkaMessage>of()), scopeHeader, scope);
        if (published.isEmpty() && received.isEmpty()) {
            return;
        }
        String scopeNote = MessageTrafficReport.scopeNote(scopeHeader, scope);
        ArtifactSink sink = bean(spring, ArtifactSink.class).orElse(ArtifactSink.NO_OP);
        sink.write(SOURCE, "failed-test-kafka", MessageTrafficReport.fileName(context), "text/markdown",
                render(context.getDisplayName(), failure, MessageTrafficReport.tail(published, last),
                        MessageTrafficReport.tail(received, last), scopeNote,
                        properties.diagnostics().payloads()));
    }

    static String render(String test, Throwable failure, List<KafkaMessage> published, List<KafkaMessage> received,
                         String scopeNote, boolean payloads) {
        return MessageTrafficReport.render("Kafka", test, failure, published, received, scopeNote, payloads);
    }

    private static <T> Optional<T> bean(ApplicationContext spring, Class<T> type) {
        return Optional.ofNullable(spring.getBeanProvider(type).getIfAvailable());
    }

    private static Optional<ApplicationContext> spring(ExtensionContext context) {
        try {
            return Optional.of(SpringExtension.getApplicationContext(context));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
