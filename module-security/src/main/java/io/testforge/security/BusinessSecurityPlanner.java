package io.testforge.security;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.security.BusinessSecuritySuite.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** No traffic: candidate cells and schema hints, never inferred business permissions. */
public final class BusinessSecurityPlanner {
    public record ProposedStep(String phase, String operation, String persona) {}

    public record Candidate(
            String operation,
            String role,
            Kind kind,
            List<String> schemaHints,
            List<ProposedStep> suggestedSequence,
            String status) {
        public Candidate {
            schemaHints = List.copyOf(schemaHints);
            suggestedSequence = List.copyOf(suggestedSequence);
        }
    }

    public List<Candidate> propose(OpenAPI document, BusinessSecuritySuite suite, int maxCells) {
        var operations = new OperationSelector().select("business", document);
        if ((long) operations.size() * suite.roles().size() * suite.checks().size() > maxCells)
            throw new IllegalArgumentException("business matrix exceeds max-cells");
        Map<String, ExplorableOperation> reads = new java.util.HashMap<>();
        operations.stream()
                .filter(op -> op.method().equals("GET"))
                .forEach(op -> reads.put(op.pathTemplate(), op));
        List<Candidate> result = new ArrayList<>();
        for (var operation : operations) {
            var hints = hints(operation);
            for (String role : suite.roles())
                for (Kind kind : suite.checks().stream().sorted().toList())
                    result.add(
                            new Candidate(
                                    safe(operation.method(), operation.pathTemplate()),
                                    role,
                                    kind,
                                    hints,
                                    sequence(operation, reads, kind),
                                    "needs_project_policy"));
        }
        return List.copyOf(result);
    }

    private List<ProposedStep> sequence(
            ExplorableOperation op, Map<String, ExplorableOperation> reads, Kind kind) {
        if (List.of("GET", "HEAD", "OPTIONS").contains(op.method())
                && List.of(Kind.PROPERTY_WRITE, Kind.TRANSITION, Kind.REPLAY).contains(kind))
            return List.of();
        String parent = op.pathTemplate().replaceFirst("/[^/]+$", "");
        ExplorableOperation control = reads.getOrDefault(op.pathTemplate(), reads.get(parent));
        String read =
                control == null
                        ? "project control required"
                        : safe(control.method(), control.pathTemplate());
        String target = safe(op.method(), op.pathTemplate());
        List<ProposedStep> steps = new ArrayList<>();
        steps.add(
                new ProposedStep(
                        "CONTROL",
                        kind == Kind.REPLAY ? target : read,
                        kind == Kind.OBJECT_ACCESS
                                        || kind == Kind.TENANT_ACCESS
                                        || kind == Kind.PROPERTY_READ
                                ? "owner role required"
                                : "same role"));
        if (kind == Kind.REPLAY)
            steps.add(new ProposedStep("CONTROL", read, "capture state after first success"));
        steps.add(new ProposedStep("PROBE", target, "declared actor role"));
        if (!List.of("GET", "HEAD", "OPTIONS").contains(op.method()))
            steps.add(new ProposedStep("VERIFY", read, "owner role required"));
        return List.copyOf(steps);
    }

    private List<String> hints(ExplorableOperation operation) {
        List<String> hints = new ArrayList<>();
        if (operation.parameters().stream().anyMatch(p -> "path".equals(p.getIn())))
            hints.add("path identifier: supply owned and foreign fixtures");
        if (operation.operation().getRequestBody() != null)
            hints.add("request body: declare protected fields and persisted-state verification");
        if (operation.operation().getResponses() != null)
            operation.operation().getResponses().values().stream()
                    .limit(10)
                    .forEach(
                            response -> {
                                if (response.getContent() == null) return;
                                response.getContent().values().stream()
                                        .limit(10)
                                        .forEach(
                                                media -> {
                                                    if (media.getSchema() == null) return;
                                                    addFieldHints(media.getSchema(), hints);
                                                });
                            });
        return hints.stream().distinct().sorted().limit(30).toList();
    }

    @SuppressWarnings("rawtypes")
    private void addFieldHints(Schema<?> schema, List<String> hints) {
        Map<String, Schema> fields = schema.getProperties();
        if (fields == null) return;
        fields.entrySet().stream()
                .limit(100)
                .forEach(
                        entry -> {
                            Schema<?> field = entry.getValue();
                            if (field == null) return;
                            String name = SecurityTargets.field(entry.getKey());
                            if (Boolean.TRUE.equals(field.getWriteOnly()))
                                hints.add("writeOnly response field: " + name);
                            if (Boolean.TRUE.equals(field.getReadOnly()))
                                hints.add("readOnly field: " + name);
                        });
    }

    static String safe(String method, String path) {
        StringBuilder result = new StringBuilder(method.toUpperCase(Locale.ROOT) + " ");
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (segment.matches("\\{[a-zA-Z][a-zA-Z0-9_]{0,39}}"))
                result.append('/').append(segment);
            else
                result.append(
                        SecurityTargets.endpoint("GET", "http://structural.test/" + segment)
                                .substring(4));
        }
        return result.toString().endsWith(" ") ? result + "/" : result.toString();
    }
}
