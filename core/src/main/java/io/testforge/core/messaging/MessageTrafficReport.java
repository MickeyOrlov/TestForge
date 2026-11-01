package io.testforge.core.messaging;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * The Markdown a broker adapter's diagnostics extension publishes for a failed
 * test: what the test published, then what arrived while it ran — with a scope
 * header configured and set, only messages carrying the scenario's scope.
 *
 * <p>Positions, keys, timestamps and header names; a payload only when the
 * caller asks for it, cut at {@value #MAX_PAYLOAD_CHARS} characters. Every
 * broker's report reads the same, so a run with two brokers is read once.
 */
public final class MessageTrafficReport {

    public static final int MAX_PAYLOAD_CHARS = 500;

    private MessageTrafficReport() {
    }

    /** The messages that belong to this scenario: all of them without a scope header or scope. */
    public static <M extends BrokerMessage> List<M> ownMessages(List<M> received, String scopeHeader,
                                                                Optional<String> scope) {
        return received.stream()
                .filter(message -> scopeHeader == null || scope.isEmpty()
                        || scope.get().equals(message.headers().get(scopeHeader)))
                .toList();
    }

    /** What the "received" section covers, for its heading. */
    public static String scopeNote(String scopeHeader, Optional<String> scope) {
        return scopeHeader != null && scope.isPresent()
                ? "only messages with " + scopeHeader + "=" + scope.get()
                : "every message buffered while the test ran";
    }

    /** The last {@code last} messages, oldest first. */
    public static <T> List<T> tail(List<T> messages, int last) {
        return messages.size() <= last ? messages : messages.subList(messages.size() - last, messages.size());
    }

    public static String render(String broker, String test, Throwable failure,
                                List<? extends BrokerMessage> published, List<? extends BrokerMessage> received,
                                String scopeNote, boolean payloads) {
        StringBuilder out = new StringBuilder("# ").append(broker).append(" traffic before `").append(test)
                .append("` failed\n\n");
        out.append("Failure: ").append(String.valueOf(failure.getMessage()).lines().findFirst().orElse(""))
                .append("\n\n");
        out.append("## Published by the test (").append(published.size()).append(")\n\n");
        published.forEach(message -> line(out, message, payloads));
        out.append("\n## Received while it ran (").append(received.size()).append("; ").append(scopeNote)
                .append(")\n\n");
        received.forEach(message -> line(out, message, payloads));
        return out.toString();
    }

    /** {@code OrderEventsTest-paysOnce}: a file name for the test's report. */
    public static String fileName(ExtensionContext context) {
        String name = context.getTestMethod()
                .map(method -> method.getDeclaringClass().getSimpleName() + "-" + method.getName())
                .orElseGet(context::getDisplayName);
        String safe = name.replaceAll("[^a-zA-Z0-9._-]+", "-").replaceAll("(^-+|-+$)", "");
        return (safe.isBlank() ? "test" : safe) + ".md";
    }

    private static void line(StringBuilder out, BrokerMessage message, boolean payloads) {
        out.append("- `").append(message.position()).append("` key=").append(message.key()).append(' ')
                .append(message.timestamp()).append(" headers=").append(message.headers().keySet()).append('\n');
        if (payloads && message.value() != null && !message.value().isEmpty()) {
            String value = message.value().length() > MAX_PAYLOAD_CHARS
                    ? message.value().substring(0, MAX_PAYLOAD_CHARS) + "…" : message.value();
            out.append("  ```\n  ").append(value.replace("\n", "\n  ")).append("\n  ```\n");
        }
    }
}
