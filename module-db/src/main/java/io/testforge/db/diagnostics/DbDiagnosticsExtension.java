package io.testforge.db.diagnostics;

import io.testforge.artifact.ArtifactSink;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * When a test fails, publishes the SQL it ran — oldest first, with bound
 * parameters — to the run index as {@code module-db/<test>.md}. A wrong
 * assertion on a row is much shorter to read next to the query that produced
 * it.
 *
 * <pre>{@code
 * @SpringBootTest(properties = "forge.db.diagnostics.enabled=true")
 * @ExtendWith(DbDiagnosticsExtension.class)
 * class OrdersTest { ... }
 * }</pre>
 *
 * The original failure is always rethrown unchanged; a diagnostics problem is
 * attached to it as suppressed. Outside a Spring test, or with diagnostics
 * disabled, it does nothing.
 */
public class DbDiagnosticsExtension implements BeforeEachCallback, TestExecutionExceptionHandler {

    static final String SOURCE = "module-db";

    @Override
    public void beforeEach(ExtensionContext context) {
        journal(context).ifPresent(SqlJournal::clear);
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

    private static void publish(ExtensionContext context, List<SqlJournal.Statement> statements, Throwable failure) {
        if (statements.isEmpty()) {
            return;
        }
        ArtifactSink sink = spring(context).map(spring -> spring.getBeanProvider(ArtifactSink.class)
                .getIfAvailable(() -> ArtifactSink.NO_OP)).orElse(ArtifactSink.NO_OP);
        sink.write(SOURCE, "failed-test-sql", safeName(context) + ".md", "text/markdown",
                render(context.getDisplayName(), statements, failure));
    }

    static String render(String test, List<SqlJournal.Statement> statements, Throwable failure) {
        StringBuilder out = new StringBuilder("# SQL before `").append(test).append("` failed\n\n");
        String message = String.valueOf(failure.getMessage());
        out.append("Failure: ").append(message.lines().findFirst().orElse("")).append("\n\n");
        int index = 1;
        for (SqlJournal.Statement statement : statements) {
            out.append("## ").append(index++).append(". ").append(statement.dataSource())
                    .append(" (").append(statement.millis()).append(" ms)")
                    .append(statement.error() == null ? "" : " — failed").append("\n\n");
            out.append("```sql\n").append(statement.sql().strip()).append("\n```\n\n");
            if (statement.parameters().size() == 1) {
                out.append("Parameters: ").append(statement.parameters().get(0)).append("\n\n");
            } else if (statement.parameters().size() > 1) {
                out.append("Batch of ").append(statement.parameters().size()).append(":\n\n");
                statement.parameters().forEach(batch -> out.append("- ").append(batch).append('\n'));
                out.append('\n');
            }
            if (statement.error() != null) {
                out.append("Error: ").append(statement.error()).append("\n\n");
            }
        }
        return out.toString();
    }

    private static Optional<SqlJournal> journal(ExtensionContext context) {
        return spring(context).flatMap(spring ->
                Optional.ofNullable(spring.getBeanProvider(SqlJournal.class).getIfAvailable()));
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
