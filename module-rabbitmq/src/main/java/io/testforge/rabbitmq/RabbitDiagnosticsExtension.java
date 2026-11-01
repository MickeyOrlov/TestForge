package io.testforge.rabbitmq;

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
 * When a test fails, publishes its RabbitMQ traffic to the run index as
 * {@code module-rabbitmq/<test>.md}: the messages it published, then those
 * that arrived in the buffer while it ran — with the scenario's scope header
 * configured and set, only those carrying its scope. The same report as
 * {@code KafkaDiagnosticsExtension} ({@link MessageTrafficReport}).
 *
 * <pre>{@code
 * @SpringBootTest
 * @ExtendWith(RabbitDiagnosticsExtension.class)
 * class OrderEventsTest { ... }
 * }</pre>
 *
 * Positions, routing keys and header names by default; bodies only with
 * {@code forge.rabbitmq.diagnostics.payloads=true}. The original failure is
 * rethrown unchanged; a diagnostics problem is attached to it as suppressed.
 */
public class RabbitDiagnosticsExtension implements BeforeEachCallback, TestExecutionExceptionHandler {

    static final String SOURCE = "module-rabbitmq";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(RabbitDiagnosticsExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        spring(context).ifPresent(spring -> {
            bean(spring, RabbitPublisher.class).ifPresent(RabbitPublisher::clearPublished);
            bean(spring, RabbitMessageBuffer.class)
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
        RabbitProbeProperties properties = bean(spring, RabbitProbeProperties.class).orElse(null);
        Optional<RabbitPublisher> publisher = bean(spring, RabbitPublisher.class);
        Optional<RabbitMessageBuffer> buffer = bean(spring, RabbitMessageBuffer.class);
        if (properties == null || (publisher.isEmpty() && buffer.isEmpty())) {
            return;
        }
        int last = properties.diagnostics().last();
        List<RabbitMessage> published = publisher.map(RabbitPublisher::publishedByThisThread).orElse(List.of());
        Long watermark = context.getStore(NAMESPACE).get("watermark", Long.class);
        String scopeHeader = publisher.map(RabbitPublisher::scopeHeader).orElse(null);
        Optional<String> scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE);
        List<RabbitMessage> received = MessageTrafficReport.ownMessages(buffer.filter(ignored -> watermark != null)
                .map(messages -> messages.since(watermark)).orElse(List.<RabbitMessage>of()), scopeHeader, scope);
        if (published.isEmpty() && received.isEmpty()) {
            return;
        }
        ArtifactSink sink = bean(spring, ArtifactSink.class).orElse(ArtifactSink.NO_OP);
        sink.write(SOURCE, "failed-test-rabbitmq", MessageTrafficReport.fileName(context), "text/markdown",
                MessageTrafficReport.render("RabbitMQ", context.getDisplayName(), failure,
                        MessageTrafficReport.tail(published, last), MessageTrafficReport.tail(received, last),
                        MessageTrafficReport.scopeNote(scopeHeader, scope), properties.diagnostics().payloads()));
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
