package io.testforge.kafka;

import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
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
    static final int MAX_PAYLOAD_CHARS = 500;

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
        List<KafkaMessage> received = buffer.filter(ignored -> watermark != null)
                .map(messages -> messages.since(watermark)).orElse(List.of()).stream()
                .filter(message -> scopeHeader == null || scope.isEmpty()
                        || scope.get().equals(message.headers().get(scopeHeader)))
                .toList();
        if (published.isEmpty() && received.isEmpty()) {
            return;
        }
        String scopeNote = scopeHeader != null && scope.isPresent()
                ? "only messages with " + scopeHeader + "=" + scope.get()
                : "every message buffered while the test ran";
        ArtifactSink sink = bean(spring, ArtifactSink.class).orElse(ArtifactSink.NO_OP);
        sink.write(SOURCE, "failed-test-kafka", safeName(context) + ".md", "text/markdown",
                render(context.getDisplayName(), failure, tail(published, last), tail(received, last), scopeNote,
                        properties.diagnostics().payloads()));
    }

    static String render(String test, Throwable failure, List<KafkaMessage> published, List<KafkaMessage> received,
                         String scopeNote, boolean payloads) {
        StringBuilder out = new StringBuilder("# Kafka traffic before `").append(test).append("` failed\n\n");
        out.append("Failure: ").append(String.valueOf(failure.getMessage()).lines().findFirst().orElse(""))
                .append("\n\n");
        out.append("## Published by the test (").append(published.size()).append(")\n\n");
        published.forEach(message -> line(out, message, payloads));
        out.append("\n## Received while it ran (").append(received.size()).append("; ").append(scopeNote)
                .append(")\n\n");
        received.forEach(message -> line(out, message, payloads));
        return out.toString();
    }

    private static void line(StringBuilder out, KafkaMessage message, boolean payloads) {
        out.append("- `").append(message.topic()).append('/').append(message.partition()).append('@')
                .append(message.offset()).append("` key=").append(message.key()).append(' ')
                .append(message.timestamp()).append(" headers=").append(message.headers().keySet()).append('\n');
        if (payloads && message.value() != null && !message.value().isEmpty()) {
            String value = message.value().length() > MAX_PAYLOAD_CHARS
                    ? message.value().substring(0, MAX_PAYLOAD_CHARS) + "…" : message.value();
            out.append("  ```\n  ").append(value.replace("\n", "\n  ")).append("\n  ```\n");
        }
    }

    private static List<KafkaMessage> tail(List<KafkaMessage> messages, int last) {
        return messages.size() <= last ? messages : messages.subList(messages.size() - last, messages.size());
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

    private static String safeName(ExtensionContext context) {
        String name = context.getTestMethod()
                .map(method -> method.getDeclaringClass().getSimpleName() + "-" + method.getName())
                .orElseGet(context::getDisplayName);
        String safe = name.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "test" : safe;
    }
}
