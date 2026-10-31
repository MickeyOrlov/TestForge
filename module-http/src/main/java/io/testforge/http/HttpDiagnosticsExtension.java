package io.testforge.http;

import io.testforge.artifact.ArtifactSink;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * When a test fails, publishes the HTTP exchanges it made — redacted, oldest
 * first — to the run index as {@code module-http/<test>.md}, so a red CI run
 * shows what was sent and what came back without rerunning anything.
 *
 * <pre>{@code
 * @SpringBootTest
 * @ExtendWith(HttpDiagnosticsExtension.class)
 * class OrdersTest { ... }
 * }</pre>
 *
 * The original failure is always rethrown unchanged; a diagnostics problem is
 * attached to it as suppressed, never in its place. Outside a Spring test, or
 * with diagnostics disabled, it does nothing.
 */
public class HttpDiagnosticsExtension implements BeforeEachCallback, TestExecutionExceptionHandler {

    static final String SOURCE = "module-http";

    @Override
    public void beforeEach(ExtensionContext context) {
        journal(context).ifPresent(HttpExchangeJournal::clear);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext context, Throwable failure) throws Throwable {
        try {
            journal(context).ifPresent(journal -> publish(context, journal.recent(), failure));
        } catch (RuntimeException e) {
            failure.addSuppressed(e);
        }
        throw failure;
    }

    private static void publish(ExtensionContext context, List<HttpExchangeJournal.Exchange> exchanges,
                                Throwable failure) {
        if (exchanges.isEmpty()) {
            return;
        }
        ArtifactSink sink = spring(context).map(spring -> spring.getBeanProvider(ArtifactSink.class)
                .getIfAvailable(() -> ArtifactSink.NO_OP)).orElse(ArtifactSink.NO_OP);
        sink.write(SOURCE, "failed-test-http", safeName(context) + ".md", "text/markdown",
                render(context.getDisplayName(), exchanges, failure));
    }

    static String render(String test, List<HttpExchangeJournal.Exchange> exchanges, Throwable failure) {
        StringBuilder out = new StringBuilder("# HTTP exchanges before `").append(test).append("` failed\n\n");
        String message = failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage();
        out.append("Failure: ").append(message.lines().findFirst().orElse("")).append("\n\n");
        int index = 1;
        for (HttpExchangeJournal.Exchange exchange : exchanges) {
            out.append("## ").append(index++).append(". ").append(exchange.method()).append(' ').append(exchange.uri())
                    .append(" → ").append(exchange.status() == null ? "no response" : exchange.status())
                    .append(" (").append(exchange.millis()).append(" ms)\n\n");
            if (exchange.auth() != null) {
                out.append("As: ").append(exchange.auth()).append("\n\n");
            }
            if (exchange.error() != null) {
                out.append("Error: ").append(exchange.error()).append("\n\n");
            }
            if (!exchange.requestHeaders().isEmpty()) {
                out.append("Request headers:\n\n");
                exchange.requestHeaders().forEach((name, value) ->
                        out.append("- `").append(name).append(": ").append(value).append("`\n"));
                out.append('\n');
            }
            appendBody(out, "Request body", exchange.requestBody());
            appendBody(out, "Response body", exchange.responseBody());
        }
        return out.toString();
    }

    private static void appendBody(StringBuilder out, String title, String body) {
        if (body != null) {
            out.append(title).append(":\n\n```\n").append(body).append("\n```\n\n");
        }
    }

    private static java.util.Optional<HttpExchangeJournal> journal(ExtensionContext context) {
        return spring(context).flatMap(spring ->
                java.util.Optional.ofNullable(spring.getBeanProvider(HttpExchangeJournal.class).getIfAvailable()));
    }

    private static java.util.Optional<ApplicationContext> spring(ExtensionContext context) {
        try {
            return java.util.Optional.of(SpringExtension.getApplicationContext(context));
        } catch (RuntimeException | LinkageError e) {
            return java.util.Optional.empty();
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
