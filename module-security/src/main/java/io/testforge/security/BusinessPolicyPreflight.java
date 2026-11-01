package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.security.BusinessPolicyReport.Issue;
import io.testforge.security.BusinessPolicyReport.Status;
import io.testforge.security.BusinessSecuritySuite.Match;
import io.testforge.security.BusinessSecuritySuite.Phase;
import io.testforge.security.BusinessSecuritySuite.Step;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Pure structural/data-flow checks; no token fetch, request or fixture preparation. */
final class BusinessPolicyPreflight {
    private final BusinessSecurityProperties business;
    private final BusinessPolicyProperties policy;
    private final Set<String> roles;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Issue> issues = new ArrayList<>();
    private BusinessPolicyReview snapshot;

    private enum Pointer {
        PRESENT,
        MISSING,
        UNKNOWN
    }

    BusinessPolicyPreflight(
            BusinessSecurityProperties business,
            BusinessPolicyProperties policy,
            Set<String> roles) {
        this.business = business;
        this.policy = policy;
        this.roles = roles;
    }

    List<Issue> check(
            OpenAPI document,
            BusinessSecuritySuite suite,
            BusinessPolicyReview snapshot,
            Set<String> inputs) {
        this.snapshot = snapshot;
        issues.clear();
        if (suite.cases().size() > business.maxCases())
            add(null, null, Status.INVALID, "CASE_BUDGET", "policy exceeds max-cases");
        if ((long) snapshot.inventory().size() * suite.roles().size() * suite.checks().size()
                > business.maxCells()) {
            add(null, null, Status.INVALID, "CELL_BUDGET", "policy exceeds max-cells");
            return List.copyOf(issues);
        }
        for (String input : inputs)
            try {
                BusinessSecuritySuite.identifier(input);
            } catch (RuntimeException e) {
                add(
                        null,
                        null,
                        Status.INVALID,
                        "INPUT_ALIAS",
                        "input names must be non-sensitive policy aliases");
            }
        Map<String, io.testforge.api.explorer.ExplorableOperation> operations = new TreeMap<>();
        new OperationSelector()
                .select("policy", document)
                .forEach(op -> operations.put(op.key(), op));
        Set<String> cells = new HashSet<>(), exclusions = new HashSet<>(), ids = new HashSet<>();
        int planned = 0;
        for (var test : suite.cases()) {
            if (!ids.add(test.id()))
                add(
                        test.id(),
                        null,
                        Status.INVALID,
                        "DUPLICATE_CASE",
                        "case aliases must be unique");
            if (!suite.roles().contains(test.role()) || !suite.checks().contains(test.kind()))
                add(
                        test.id(),
                        null,
                        Status.INVALID,
                        "CASE_SCOPE",
                        "case lies outside declared roles or check kinds");
            cells.add(cell(test.method() + " " + test.path(), test.role(), test.kind()));
            planned += test.steps().size() + test.cleanup().size();
            try {
                new BusinessSecurityValidator(business, roles::contains, request -> {})
                        .validateCase(suite, test, operations.keySet(), inputs);
            } catch (BusinessSecurityException e) {
                add(test.id(), null, Status.INVALID, "CASE_STRUCTURE", e.getMessage());
            }
            Set<String> available = new HashSet<>(inputs), cleanupAvailable = new HashSet<>(inputs);
            boolean probed = false;
            for (Step step : test.steps()) {
                step(test, step, available, false);
                if (step.phase() == Phase.PROBE) probed = true;
                if (!probed && step.phase() == Phase.CONTROL)
                    cleanupAvailable.addAll(step.captures().keySet());
                available.addAll(step.captures().keySet());
            }
            for (Step step : test.cleanup()) {
                step(test, step, cleanupAvailable, true);
                // Every cleanup is attempted even when an earlier cleanup failed: do not rely on
                // its captures.
            }
        }
        if (planned > business.maxRequests())
            add(
                    null,
                    null,
                    Status.INVALID,
                    "REQUEST_BUDGET",
                    "planned requests including cleanup exceed max-requests");
        for (var exclusion : suite.exclusions()) {
            String op = exclusion.method() + " " + exclusion.path();
            String key = cell(op, exclusion.role(), exclusion.kind());
            if (!operations.containsKey(op)
                    || !suite.roles().contains(exclusion.role())
                    || !suite.checks().contains(exclusion.kind()))
                add(
                        null,
                        null,
                        Status.INVALID,
                        "EXCLUSION_SCOPE",
                        "exclusion refers outside the current document or policy scope");
            if (!exclusions.add(key) || cells.contains(key))
                add(
                        null,
                        null,
                        Status.INVALID,
                        "EXCLUSION_CONFLICT",
                        "exclusion is duplicated or conflicts with a case");
        }
        for (String operation : snapshot.inventory())
            for (String role : suite.roles())
                for (var kind : suite.checks())
                    if (!cells.contains(cell(operation, role, kind))
                            && !exclusions.contains(cell(operation, role, kind))) {
                        add(
                                null,
                                null,
                                Status.REVIEW_REQUIRED,
                                "COVERAGE_UNDECLARED",
                                "current document has a cell without a case or explicit exclusion",
                                operation + " role " + role + " " + kind);
                        if (issues.size() > 1000) return List.copyOf(issues);
                    }
        return List.copyOf(issues);
    }

    private void step(
            BusinessSecuritySuite.Case test, Step step, Set<String> available, boolean cleanup) {
        String key =
                step.request().method().toUpperCase(java.util.Locale.ROOT)
                        + " "
                        + step.request().pathTemplate();
        JsonNode operation = snapshot.operations().get(key);
        if (!roles.contains(step.role()))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "UNKNOWN_ROLE",
                    "role alias is not configured; credentials were not fetched",
                    step.role());
        if (operation == null) {
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "OPERATION_MISSING",
                    "step operation is absent from the current OpenAPI document");
            return;
        }
        var pathNames = new HashSet<String>();
        var matcher = Pattern.compile("\\{([^}]+)}").matcher(step.request().pathTemplate());
        while (matcher.find()) pathNames.add(matcher.group(1));
        if (!step.request().pathParameters().keySet().containsAll(pathNames))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "PATH_PARAMETER_MISSING",
                    "templated path has an unbound parameter");
        if (!pathNames.containsAll(step.request().pathParameters().keySet()))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "PATH_PARAMETER_UNUSED",
                    "request declares a path parameter absent from its route");
        for (var parameter : operation.path("parameters"))
            if (parameter.path("required").asBoolean()) {
                String name = parameter.path("name").asText();
                switch (parameter.path("in").asText()) {
                    case "path" -> {
                        if (!step.request().pathParameters().containsKey(name))
                            add(
                                    test.id(),
                                    step.id(),
                                    Status.INVALID,
                                    "PATH_PARAMETER_MISSING",
                                    "required path parameter is not bound",
                                    "path." + name);
                    }
                    case "query" -> {
                        if (!step.request().queryParameters().containsKey(name))
                            add(
                                    test.id(),
                                    step.id(),
                                    Status.INVALID,
                                    "QUERY_PARAMETER_MISSING",
                                    "required query parameter is not bound",
                                    "query." + name);
                    }
                    default ->
                            add(
                                    test.id(),
                                    step.id(),
                                    Status.REVIEW_REQUIRED,
                                    "PARAMETER_PLACEMENT",
                                    "required header or cookie needs project credential-placement"
                                            + " validation");
                }
            }
        if (step.request().body() != null
                && Set.of("GET", "HEAD", "OPTIONS")
                        .contains(step.request().method().toUpperCase(java.util.Locale.ROOT)))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "READ_BODY",
                    "business read operations cannot carry bodies");
        if (step.request().body() != null
                && step.request().contentType() != null
                && !step.request().contentType().equals("application/json"))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "BODY_CONTENT_TYPE",
                    "business request bodies require application/json");
        if (step.request().queryParameters().keySet().stream()
                .anyMatch(
                        name ->
                                name.contains("%")
                                        || Set.of(
                                                        "_method",
                                                        "method",
                                                        "httpmethod",
                                                        "x-http-method-override",
                                                        "x-method-override",
                                                        "x-http-method")
                                                .contains(name.toLowerCase(java.util.Locale.ROOT))))
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "METHOD_OVERRIDE",
                    "method-override query parameters are forbidden");
        if (operation.path("request").path("required").asBoolean() && step.request().body() == null)
            add(
                    test.id(),
                    step.id(),
                    Status.INVALID,
                    "BODY_MISSING",
                    "required request body has no template");
        step.request()
                .pathParameters()
                .values()
                .forEach(
                        value ->
                                refs(
                                        mapper.getNodeFactory().textNode(value),
                                        test.id(),
                                        step.id(),
                                        available,
                                        cleanup,
                                        0));
        step.request()
                .queryParameters()
                .values()
                .forEach(
                        value ->
                                refs(
                                        mapper.getNodeFactory().textNode(value),
                                        test.id(),
                                        step.id(),
                                        available,
                                        cleanup,
                                        0));
        if (step.request().body() != null) {
            if (step.request().body().getBytes(StandardCharsets.UTF_8).length
                    > business.maxBodyBytes())
                add(
                        test.id(),
                        step.id(),
                        Status.INVALID,
                        "BODY_BUDGET",
                        "body template exceeds its byte budget");
            else
                try {
                    refs(
                            mapper.readTree(step.request().body()),
                            test.id(),
                            step.id(),
                            available,
                            cleanup,
                            0);
                } catch (Exception e) {
                    add(
                            test.id(),
                            step.id(),
                            Status.INVALID,
                            "BODY_JSON",
                            "body template must be JSON");
                }
        }
        for (var assertion : step.assertions()) {
            refs(assertion.expected(), test.id(), step.id(), available, cleanup, 0);
            pointer(
                    test.id(),
                    step,
                    operation,
                    assertion.pointer(),
                    assertion.match() == Match.ABSENT);
        }
        step.captures()
                .values()
                .forEach(pointer -> pointer(test.id(), step, operation, pointer, false));
    }

    private void refs(
            JsonNode value,
            String caseId,
            String stepId,
            Set<String> available,
            boolean cleanup,
            int depth) {
        if (value == null) return;
        if (depth > policy.maxDepth()) {
            add(
                    caseId,
                    stepId,
                    Status.INVALID,
                    "TEMPLATE_DEPTH",
                    "binding template exceeds max-depth");
            return;
        }
        if (value.isTextual() && value.textValue().contains("${")) {
            String text = value.textValue();
            if (!text.matches("\\$\\{[a-zA-Z][a-zA-Z0-9_-]{0,63}}"))
                add(
                        caseId,
                        stepId,
                        Status.INVALID,
                        "PARTIAL_BINDING",
                        "only an entire value can be a binding; interpolation is unsupported");
            else if (!available.contains(text.substring(2, text.length() - 1)))
                add(
                        caseId,
                        stepId,
                        Status.INVALID,
                        cleanup ? "CLEANUP_BINDING" : "BINDING_UNAVAILABLE",
                        cleanup
                                ? "cleanup must use inputs or captures from pre-probe controls"
                                : "binding is not an input or an earlier capture",
                        text.substring(2, text.length() - 1));
        }
        if (value.isContainerNode())
            value.forEach(child -> refs(child, caseId, stepId, available, cleanup, depth + 1));
    }

    private void pointer(
            String caseId, Step step, JsonNode operation, String pointer, boolean absent) {
        List<JsonNode> schemas = new ArrayList<>();
        for (int status : step.statuses()) {
            var responses = operation.path("responses");
            JsonNode response = responses.get(String.valueOf(status));
            if (response == null) response = responses.get((status / 100) + "XX");
            if (response == null) response = responses.get("default");
            if (response != null)
                response.properties()
                        .forEach(
                                entry -> {
                                    if (entry.getKey().equals("application/json")
                                            || entry.getKey().endsWith("+json"))
                                        schemas.add(entry.getValue());
                                });
        }
        if (step.request().method().equalsIgnoreCase("HEAD")
                || step.statuses().stream().allMatch(status -> status == 204 || status == 304)) {
            add(
                    caseId,
                    step.id(),
                    Status.INVALID,
                    "NO_JSON_BODY",
                    "capture or assertion expects JSON on a bodyless response");
            return;
        }
        if (schemas.isEmpty()) {
            add(
                    caseId,
                    step.id(),
                    Status.REVIEW_REQUIRED,
                    "JSON_SCHEMA_UNKNOWN",
                    "expected JSON response schema is not declared");
            return;
        }
        if (absent) return; // A forbidden field need not be documented to assert its absence.
        List<String> tokens =
                pointer.isEmpty()
                        ? List.of()
                        : java.util.Arrays.stream(pointer.substring(1).split("/", -1))
                                .map(token -> token.replace("~1", "/").replace("~0", "~"))
                                .toList();
        boolean present = false, unknown = false;
        for (JsonNode schema : schemas) {
            Pointer result = lookup(schema, tokens, operation.path("components"), 0);
            present |= result == Pointer.PRESENT;
            unknown |= result == Pointer.UNKNOWN;
        }
        if (!present)
            add(
                    caseId,
                    step.id(),
                    unknown ? Status.REVIEW_REQUIRED : Status.INVALID,
                    unknown ? "POINTER_UNVERIFIED" : "POINTER_MISSING",
                    unknown
                            ? "response schema cannot establish the declared pointer"
                            : "capture or assertion pointer is absent from the response schema",
                    pointer);
    }

    private Pointer lookup(JsonNode schema, List<String> tokens, JsonNode components, int depth) {
        if (depth > policy.maxDepth()
                || schema == null
                || schema.path("unknown").asBoolean()
                || schema.path("recursive").asBoolean()) return Pointer.UNKNOWN;
        if (schema.has("ref"))
            return lookup(
                    components.get(schema.path("ref").asText()), tokens, components, depth + 1);
        if (tokens.isEmpty()) return Pointer.PRESENT;
        String head = tokens.getFirst();
        List<String> tail = tokens.subList(1, tokens.size());
        if (schema.path("properties").has(head))
            return lookup(schema.path("properties").get(head), tail, components, depth + 1);
        if (schema.has("items") && head.matches("[0-9]+"))
            return lookup(schema.get("items"), tail, components, depth + 1);
        boolean unknown = false, composed = false;
        for (String name : List.of("allOf", "anyOf", "oneOf"))
            for (JsonNode option : schema.path(name)) {
                composed = true;
                Pointer value = lookup(option, tokens, components, depth + 1);
                if (value == Pointer.PRESENT) return value;
                unknown |= value == Pointer.UNKNOWN;
            }
        if (composed) return unknown ? Pointer.UNKNOWN : Pointer.MISSING;
        if (schema.has("additionalSchema"))
            return lookup(schema.get("additionalSchema"), tail, components, depth + 1);
        if (schema.has("properties") || schema.path("type").asText().equals("object"))
            return schema.path("additionalAllowed").asBoolean(true)
                    ? Pointer.UNKNOWN
                    : Pointer.MISSING;
        return schema.has("type") ? Pointer.MISSING : Pointer.UNKNOWN;
    }

    private void add(String caseId, String step, Status status, String code, String reason) {
        add(caseId, step, status, code, reason, null);
    }

    private void add(
            String caseId,
            String step,
            Status status,
            String code,
            String reason,
            String location) {
        if (issues.size() < 1000)
            issues.add(new Issue(caseId, step, status, code, reason, location));
        else if (issues.size() == 1000)
            issues.add(
                    new Issue(
                            null,
                            null,
                            Status.REVIEW_REQUIRED,
                            "ISSUE_LIMIT",
                            "additional issues omitted; resolve reported issues and rerun"));
    }

    private static String cell(String operation, String role, BusinessSecuritySuite.Kind kind) {
        return operation + " " + role + " " + kind;
    }
}
