package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import io.testforge.artifact.ArtifactSink;
import io.testforge.auth.AuthContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calls every operation of every configured document once per role and holds
 * each answer to the declared {@link AccessRule}.
 *
 * <p>A role that gets {@code 200} where it should get {@code 403} is a hole in
 * the product's authorization; a role shut out of what it should reach is a
 * regression. Both fail {@link #assertHealthy()}. Undeclared cells only fail
 * it with {@code fail-on-undeclared}; until then the report proposes the
 * expectations the run observed, ready to review and paste.
 *
 * <p>Requests are planned exactly as the explorer plans them — same parameter
 * values, same method safety policy — and sent through {@code ApiClient}, so
 * authentication goes through {@code module-auth}'s filter like in any test.
 * Only statuses are recorded: response bodies never reach the report.
 */
public class AccessMatrixRunner {

    private static final Logger log = LoggerFactory.getLogger(AccessMatrixRunner.class);
    private static final String SOURCE = "module-api-explorer";

    private final OpenApiSpecParser parser;
    private final ApiDiscoveryProperties discovery;
    private final AccessMatrixProperties properties;
    private final OperationSelector selector = new OperationSelector();
    private final SafetyPolicy safety;
    private final RequestPlanner planner;
    private final ExchangeExecutor executor;
    private final Set<String> knownRoles;
    private final ObjectMapper objectMapper;
    private final ArtifactSink artifactSink;

    /**
     * @param knownRoles roles {@code module-auth} can issue tokens for; {@code null}
     *                   when it is not configured, so roles are not checked up front
     */
    public AccessMatrixRunner(OpenApiSpecParser parser, ApiDiscoveryProperties discovery,
                              ApiExplorerProperties explorer, AccessMatrixProperties properties,
                              ExchangeExecutor executor, Set<String> knownRoles,
                              ObjectMapper objectMapper, ArtifactSink artifactSink) {
        this.parser = parser;
        this.discovery = discovery;
        this.properties = properties;
        this.safety = new SafetyPolicy(explorer);
        this.planner = new RequestPlanner(new RequestValueResolver(explorer.parameters(), new SchemaValueFactory()));
        this.executor = executor;
        this.knownRoles = knownRoles;
        this.objectMapper = objectMapper;
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    public AccessMatrixReport run() {
        List<String> roles = roles();
        String baseUrl = executor.baseUrl();
        log.warn("Access matrix will call every selected operation as {} against {}", roles, baseUrl);

        List<AccessRow> rows = new ArrayList<>();
        for (ApiSpecSource source : sources()) {
            for (ExplorableOperation operation : selector.select(source.id(), parser.parse(source))) {
                rows.add(row(operation, roles));
            }
        }

        Path outputDir = Path.of(properties.outputDir());
        Path json = outputDir.resolve("access-matrix.json");
        Path markdown = outputDir.resolve("access-matrix.md");
        int violations = count(rows, AccessCell.Verdict.VIOLATION);
        int errors = count(rows, AccessCell.Verdict.ERROR);
        int undeclared = count(rows, AccessCell.Verdict.UNDECLARED);
        boolean healthy = violations == 0 && errors == 0 && (undeclared == 0 || !properties.failOnUndeclared());
        AccessMatrixReport report = new AccessMatrixReport(Instant.now().toString(), baseUrl, roles, healthy,
                violations, errors, undeclared, count(rows, AccessCell.Verdict.SKIPPED), rows,
                json.toString(), markdown.toString());

        write(json, () -> objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        write(markdown, () -> AccessMatrixMarkdown.render(report));
        Map<String, String> metadata = Map.of("healthy", String.valueOf(healthy),
                "violations", String.valueOf(violations));
        artifactSink.registerFile(SOURCE, "access-matrix", "access-matrix.json", json, "application/json", metadata);
        artifactSink.registerFile(SOURCE, "access-matrix", "access-matrix.md", markdown, "text/markdown", metadata);
        return report;
    }

    /** Fails with every violation listed; the report files hold the full matrix. */
    public AccessMatrixReport assertHealthy() {
        AccessMatrixReport report = run();
        if (!report.healthy()) {
            StringBuilder message = new StringBuilder("Access matrix: ")
                    .append(report.violations()).append(" violations, ")
                    .append(report.errors()).append(" errors, ")
                    .append(report.undeclared()).append(" undeclared. See ").append(report.reportMarkdown());
            report.violationLines().forEach(line -> message.append("\n  - ").append(line));
            throw new AssertionError(message.toString());
        }
        return report;
    }

    private AccessRow row(ExplorableOperation operation, List<String> roles) {
        Optional<SkipReason> refusal = safety.refuse(operation);
        PlannedRequest plan = refusal.isPresent()
                ? PlannedRequest.skip(refusal.get(), null, List.of())
                : planner.plan(operation);

        List<AccessCell> cells = new ArrayList<>();
        for (String role : roles) {
            AccessRule rule = properties.rule(operation.operationId(), role).orElse(null);
            if (!plan.sendable()) {
                String detail = plan.skipReason().description() + (plan.detail() == null ? "" : ": " + plan.detail());
                cells.add(new AccessCell(role, 0, rule == null ? null : rule.text(), AccessCell.Verdict.SKIPPED,
                        detail));
                continue;
            }
            RuntimeExchange exchange = AuthContext.callAs(role, () -> executor.execute(plan.request()));
            cells.add(AccessCell.judge(role, exchange.status(), rule, exchange.error()));
        }
        String target = plan.sendable() ? plan.request().resolvedTarget() : null;
        return new AccessRow(operation.specId(), operation.operationId(), operation.key(), target, cells);
    }

    private List<String> roles() {
        List<String> roles = properties.declaredRoles();
        if (roles.isEmpty()) {
            if (knownRoles == null) {
                throw new IllegalStateException("forge.access-matrix names no roles and module-auth is not "
                        + "configured: list them under forge.access-matrix.roles");
            }
            List<String> all = new ArrayList<>(new java.util.TreeSet<>(knownRoles));
            all.add(AuthContext.ANONYMOUS);
            return List.copyOf(all);
        }
        if (knownRoles != null) {
            List<String> unknown = roles.stream()
                    .filter(role -> !AuthContext.ANONYMOUS.equals(role) && !knownRoles.contains(role))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalStateException("forge.access-matrix names roles that forge.auth.roles does not "
                        + "configure: " + unknown + " (configured: " + new java.util.TreeSet<>(knownRoles) + ")");
            }
        }
        return roles;
    }

    private List<ApiSpecSource> sources() {
        return discovery.specs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> properties.specs().isEmpty() || properties.specs().contains(entry.getKey()))
                .map(entry -> new ApiSpecSource(entry.getKey(), entry.getValue().location()))
                .toList();
    }

    private static int count(List<AccessRow> rows, AccessCell.Verdict verdict) {
        return (int) rows.stream().flatMap(row -> row.cells().stream())
                .filter(cell -> cell.verdict() == verdict).count();
    }

    private interface Content {
        String get() throws IOException;
    }

    private static void write(Path path, Content content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content.get(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }
}
