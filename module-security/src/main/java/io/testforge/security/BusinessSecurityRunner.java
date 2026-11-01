package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.artifact.ArtifactSink;
import io.testforge.auth.AuthProperties;
import io.testforge.auth.TokenProvider;
import io.testforge.http.HttpProperties;
import io.testforge.security.BusinessSecuritySuite.Kind;
import io.testforge.security.BusinessSecuritySuite.Phase;
import io.testforge.security.BusinessSecuritySuite.Step;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Bounded ordered security cases; product setup may be supplied by module-flow/module-state. */
public class BusinessSecurityRunner implements AutoCloseable {
    private final BusinessSecurityProperties properties;
    private final SecurityProperties reports;
    private final SecurityCollector collector;
    private final ArtifactSink sink;
    private final BusinessSecurityExchange exchange;
    private final BusinessSecurityValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    public BusinessSecurityRunner(
            BusinessSecurityProperties properties,
            HttpProperties http,
            TokenProvider tokens,
            AuthProperties auth,
            SecuritySecretRegistry secrets,
            SecurityProperties reports,
            SecurityCollector collector,
            ArtifactSink sink) {
        this.properties = properties;
        this.reports = reports;
        this.collector = collector;
        this.sink = sink;
        exchange = new BusinessSecurityExchange(http, tokens, auth, properties, secrets);
        validator = new BusinessSecurityValidator(properties, exchange);
    }

    /** Scope is the entire document × roles × selected kinds; exclusions need policy references. */
    public BusinessSecurityReport run(
            OpenAPI document, BusinessSecuritySuite suite, Map<String, JsonNode> inputs) {
        collector.observedBlock("business");
        try {
            return execute(document, suite, inputs == null ? Map.of() : inputs);
        } catch (RuntimeException e) {
            collector.covered(
                    "business-run",
                    "run",
                    null,
                    "not_measured",
                    "business suite could not complete");
            if (e instanceof BusinessSecurityException safe) throw safe;
            // A third-party parser/transport error must never print a body, binding or credential.
            throw new BusinessSecurityException(
                    "business suite configuration or report failed; see coverage");
        }
    }

    private BusinessSecurityReport execute(
            OpenAPI document, BusinessSecuritySuite suite, Map<String, JsonNode> inputs) {
        if (!properties.enabled() || !properties.testEnvironment())
            throw new BusinessSecurityException(
                    "business probes need enabled and test-environment");
        var operations = new OperationSelector().select("business", document);
        if ((long) operations.size() * suite.roles().size() * suite.checks().size()
                > properties.maxCells())
            throw new BusinessSecurityException("business matrix exceeds max-cells");
        if (operations.isEmpty())
            throw new BusinessSecurityException("business scope has no operations");
        if (suite.exclusions().size() > properties.maxCells())
            throw new BusinessSecurityException("too many business exclusions");
        if (suite.cases().size() > properties.maxCases())
            throw new BusinessSecurityException("too many business cases");
        for (String role : suite.roles())
            if (!exchange.knownRole(role))
                throw new BusinessSecurityException("unknown business role");
        long inputBytes = 0;
        for (var input : inputs.entrySet()) {
            BusinessSecuritySuite.identifier(input.getKey());
            if (input.getValue() == null)
                throw new BusinessSecurityException("business input must be a JSON value");
            inputBytes += input.getValue().toString().getBytes(StandardCharsets.UTF_8).length;
            if (inputBytes > properties.maxBodyBytes())
                throw new BusinessSecurityException(
                        "business inputs exceed the case binding bound");
        }
        if (inputs.size() > properties.maxSteps())
            throw new BusinessSecurityException("too many business input bindings");
        Set<String> operationKeys = new HashSet<>();
        operations.forEach(op -> operationKeys.add(op.key()));
        Map<String, List<BusinessSecuritySuite.Case>> cases = new HashMap<>();
        Set<String> ids = new HashSet<>();
        int planned = 0;
        for (var test : suite.cases()) {
            if (!ids.add(test.id()))
                throw new BusinessSecurityException("duplicate business case id");
            requireCell(operationKeys, suite, test.method(), test.path(), test.role(), test.kind());
            validator.validateCase(suite, test, operationKeys, inputs.keySet());
            planned += test.steps().size() + test.cleanup().size();
            cases.computeIfAbsent(
                            key(test.method(), test.path(), test.role(), test.kind()),
                            ignored -> new ArrayList<>())
                    .add(test);
        }
        if (planned > properties.maxRequests())
            throw new BusinessSecurityException("planned business work exceeds request budget");
        Map<String, String> excluded = new HashMap<>();
        for (var exclusion : suite.exclusions()) {
            requireCell(
                    operationKeys,
                    suite,
                    exclusion.method(),
                    exclusion.path(),
                    exclusion.role(),
                    exclusion.kind());
            String key =
                    key(exclusion.method(), exclusion.path(), exclusion.role(), exclusion.kind());
            if (cases.containsKey(key) || excluded.put(key, exclusion.reason()) != null)
                throw new BusinessSecurityException("conflicting business case/exclusion");
        }
        var budget = new Budget(System.nanoTime(), properties.timeout(), properties.maxRequests());
        Map<String, Boolean> completed = new HashMap<>();
        for (var test : suite.cases())
            completed.put(test.id(), executeCase(suite, test, inputs, budget));
        List<BusinessSecurityReport.Cell> cells = new ArrayList<>();
        for (var operation : operations)
            for (String role : suite.roles())
                for (var kind : suite.checks().stream().sorted().toList()) {
                    String key = key(operation.method(), operation.pathTemplate(), role, kind);
                    var tests = cases.getOrDefault(key, List.of());
                    String status =
                            excluded.containsKey(key)
                                    ? "not_applicable"
                                    : !tests.isEmpty()
                                                    && tests.stream()
                                                            .allMatch(t -> completed.get(t.id()))
                                            ? "measured"
                                            : "not_measured";
                    String reason =
                            excluded.containsKey(key)
                                    ? excluded.get(key)
                                    : tests.isEmpty()
                                            ? "no project case declared"
                                            : status.equals("measured")
                                                    ? null
                                                    : "case or cleanup incomplete";
                    String safe =
                            BusinessSecurityPlanner.safe(
                                    operation.method(), operation.pathTemplate());
                    cells.add(
                            new BusinessSecurityReport.Cell(
                                    safe,
                                    role,
                                    kind,
                                    status,
                                    reason,
                                    tests.stream().map(BusinessSecuritySuite.Case::id).toList()));
                    collector.covered(check(kind), scope(suite) + " " + safe, role, status, reason);
                }
        var report =
                new BusinessSecurityReport(
                        suite.id(),
                        suite.version(),
                        cells.stream().noneMatch(c -> c.status().equals("not_measured")),
                        budget.requests,
                        cells);
        write(report);
        return report;
    }

    private boolean executeCase(
            BusinessSecuritySuite suite,
            BusinessSecuritySuite.Case test,
            Map<String, JsonNode> inputs,
            Budget budget) {
        Map<String, JsonNode> bindings = new HashMap<>();
        inputs.forEach((name, value) -> bindings.put(name, value.deepCopy()));
        boolean complete = true;
        try {
            for (var step : test.steps())
                if (!step(suite, test, step, bindings, budget)) {
                    complete = false;
                    break;
                }
        } finally {
            for (var cleanup : test.cleanup())
                if (!step(suite, test, cleanup, bindings, budget)) complete = false;
            bindings.clear();
        }
        if (complete)
            collector.covered(
                    check(test.kind()), target(suite, test), test.role(), "measured", null);
        else
            collector.covered(
                    check(test.kind()),
                    target(suite, test),
                    test.role(),
                    "not_measured",
                    "positive control, request, binding, verification or cleanup incomplete");
        return complete;
    }

    private boolean step(
            BusinessSecuritySuite suite,
            BusinessSecuritySuite.Case test,
            Step step,
            Map<String, JsonNode> bindings,
            Budget budget) {
        try {
            PreparedRequest request = resolve(step.request(), bindings);
            Duration remaining = budget.next();
            var response = exchange.send(request, step.role(), remaining);
            int status = response.statusCode();
            if (status == 429 || status >= 500) return false;
            if (!step.statuses().contains(status)) {
                if (step.phase() != Phase.PROBE || status < 200 || status >= 300) return false;
                finding(
                        suite,
                        test,
                        "business probe accepted a request outside the declared policy");
            }
            if (step.assertions().isEmpty() && step.captures().isEmpty()) return true;
            JsonNode body = mapper.readTree(response.body());
            if (body == null) return false;
            for (var assertion : step.assertions()) {
                JsonNode actual = body.at(assertion.pointer());
                boolean matches =
                        switch (assertion.match()) {
                            case ABSENT -> actual.isMissingNode();
                            case PRESENT -> !actual.isMissingNode();
                            case EQUALS ->
                                    !actual.isMissingNode()
                                            && actual.equals(bind(assertion.expected(), bindings));
                        };
                if (!matches) {
                    if (step.phase() == Phase.CONTROL) return false;
                    finding(
                            suite,
                            test,
                            "declared business field or persisted-state invariant was violated");
                }
            }
            long bindingBytes = 0;
            for (JsonNode value : bindings.values())
                bindingBytes += value.toString().getBytes(StandardCharsets.UTF_8).length;
            for (var capture : step.captures().entrySet()) {
                JsonNode value = body.at(capture.getValue());
                if (value.isMissingNode()) return false;
                bindingBytes += value.toString().getBytes(StandardCharsets.UTF_8).length;
                if (bindingBytes > properties.maxBodyBytes()) return false;
                bindings.put(capture.getKey(), value.deepCopy());
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private PreparedRequest resolve(PreparedRequest request, Map<String, JsonNode> bindings)
            throws Exception {
        String body =
                request.body() == null
                        ? null
                        : mapper.writeValueAsString(
                                bind(mapper.readTree(request.body()), bindings));
        return new PreparedRequest(
                request.method(),
                request.pathTemplate(),
                strings(request.pathParameters(), bindings),
                strings(request.queryParameters(), bindings),
                body,
                request.contentType());
    }

    private Map<String, String> strings(
            Map<String, String> source, Map<String, JsonNode> bindings) {
        Map<String, String> result = new TreeMap<>();
        source.forEach(
                (name, value) -> {
                    JsonNode node = bind(mapper.getNodeFactory().textNode(value), bindings);
                    if (node == null || !node.isValueNode() || node.isNull())
                        throw new BusinessSecurityException("non-scalar request binding");
                    result.put(name, node.asText());
                });
        return result;
    }

    private JsonNode bind(JsonNode node, Map<String, JsonNode> bindings) {
        return bind(node, bindings, new long[] {0});
    }

    private JsonNode bind(JsonNode node, Map<String, JsonNode> bindings, long[] bytes) {
        if (node == null) throw new BusinessSecurityException("missing JSON binding");
        if (node.isTextual()
                && node.textValue().startsWith("${")
                && node.textValue().endsWith("}")) {
            JsonNode value =
                    bindings.get(node.textValue().substring(2, node.textValue().length() - 1));
            if (value == null) throw new BusinessSecurityException("missing runtime binding");
            bytes[0] += value.toString().getBytes(StandardCharsets.UTF_8).length;
            if (bytes[0] > properties.maxBodyBytes())
                throw new BusinessSecurityException("expanded binding exceeds body limit");
            return value.deepCopy();
        }
        bytes[0] +=
                node.isContainerNode()
                        ? 2
                        : node.toString().getBytes(StandardCharsets.UTF_8).length;
        if (bytes[0] > properties.maxBodyBytes())
            throw new BusinessSecurityException("expanded value exceeds body limit");
        if (node.isObject()) {
            ObjectNode result = mapper.createObjectNode();
            node.properties()
                    .forEach(
                            entry ->
                                    result.set(
                                            entry.getKey(),
                                            bind(entry.getValue(), bindings, bytes)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = mapper.createArrayNode();
            node.forEach(value -> result.add(bind(value, bindings, bytes)));
            return result;
        }
        return node.deepCopy();
    }

    private void finding(
            BusinessSecuritySuite suite, BusinessSecuritySuite.Case test, String evidence) {
        collector.finding(
                check(test.kind()),
                Severity.HIGH,
                target(suite, test),
                evidence,
                switch (test.kind()) {
                    case PROPERTY_READ, PROPERTY_WRITE -> "API3:2023";
                    case OBJECT_ACCESS, TENANT_ACCESS -> "API1:2023";
                    case TRANSITION, REPLAY -> "API6:2023";
                });
    }

    private static String check(Kind kind) {
        return "business-" + kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static String scope(BusinessSecuritySuite suite) {
        return "suite " + suite.id() + " version " + suite.version();
    }

    private static String target(BusinessSecuritySuite suite, BusinessSecuritySuite.Case test) {
        return scope(suite)
                + " case "
                + test.id()
                + " "
                + BusinessSecurityPlanner.safe(test.method(), test.path())
                + " role "
                + test.role();
    }

    private static String key(String method, String path, String role, Kind kind) {
        return method + " " + path + " " + role + " " + kind;
    }

    private static void requireCell(
            Set<String> operations,
            BusinessSecuritySuite suite,
            String method,
            String path,
            String role,
            Kind kind) {
        if (!operations.contains(method + " " + path)
                || !suite.roles().contains(role)
                || !suite.checks().contains(kind))
            throw new BusinessSecurityException(
                    "business policy references a cell outside its scope");
    }

    private void write(BusinessSecurityReport report) {
        try {
            Path dir = Path.of(reports.outputDir());
            Files.createDirectories(dir);
            Path json =
                    dir.resolve(
                            "business-"
                                    + report.suite()
                                    + "-"
                                    + report.version()
                                    + "-coverage.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            Path markdown =
                    dir.resolve(
                            "business-" + report.suite() + "-" + report.version() + "-coverage.md");
            StringBuilder content =
                    new StringBuilder(
                            "# Business security coverage\n\nSuite: "
                                    + report.suite()
                                    + " / "
                                    + report.version()
                                    + "\n\nComplete: "
                                    + report.complete()
                                    + "\n\n"
                                    + "| Operation | Role | Kind | Status | Reason | Cases |\n"
                                    + "|---|---|---|---|---|---|\n");
            for (var cell : report.cells())
                content.append("| ")
                        .append(cell.operation())
                        .append(" | ")
                        .append(cell.role())
                        .append(" | ")
                        .append(cell.kind())
                        .append(" | ")
                        .append(cell.status())
                        .append(" | ")
                        .append(cell.reason() == null ? "" : cell.reason())
                        .append(" | ")
                        .append(String.join(", ", cell.cases()))
                        .append(" |\n");
            Files.writeString(markdown, content);
            sink.registerFile(
                    "module-security",
                    "business-coverage",
                    markdown.getFileName().toString(),
                    markdown,
                    "text/markdown",
                    Map.of("complete", Boolean.toString(report.complete())));
            sink.registerFile(
                    "module-security",
                    "business-coverage",
                    json.getFileName().toString(),
                    json,
                    "application/json",
                    Map.of("complete", Boolean.toString(report.complete())));
        } catch (Exception e) {
            throw new BusinessSecurityException("business coverage report could not be written");
        }
    }

    @Override
    public void close() {
        exchange.close();
    }

    private static final class Budget {
        private final long started;
        private final Duration timeout;
        private final int limit;
        private int requests;

        Budget(long started, Duration timeout, int limit) {
            this.started = started;
            this.timeout = timeout;
            this.limit = limit;
        }

        Duration next() {
            Duration remaining = timeout.minusNanos(System.nanoTime() - started);
            if (remaining.isZero() || remaining.isNegative() || ++requests > limit)
                throw new BusinessSecurityException("business time/request budget exhausted");
            return remaining;
        }
    }
}
