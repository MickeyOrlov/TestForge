package io.testforge.security;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.links.Link;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.OperationSelector;
import io.testforge.security.BusinessScenarioReport.Binding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Local Link resolution only; no document URI, literal payload, example or server is copied. */
final class BusinessScenarioGraph {
    final Map<String, ExplorableOperation> operations = new TreeMap<>();
    final List<BusinessScenarioReport.Link> links = new ArrayList<>();
    private final BusinessScenarioProperties properties;
    private final Map<String, List<String>> operationIds = new HashMap<>();

    BusinessScenarioGraph(OpenAPI document, BusinessScenarioProperties properties) {
        this.properties = properties;
        if (document == null
                || document.getPaths() == null
                || document.getPaths().size() > properties.maxOperations())
            throw new BusinessSecurityException(
                    "scenario document operation count outside supported range");
        int count = 0;
        for (var item : document.getPaths().values()) {
            if (item == null) continue;
            count += item.readOperationsMap().size();
            if (count > properties.maxOperations()
                    || item.getParameters() != null && item.getParameters().size() > 200)
                throw new BusinessSecurityException(
                        "scenario document operation count or parameters exceed bounds");
            for (var op : item.readOperationsMap().values())
                if (op.getParameters() != null && op.getParameters().size() > 200)
                    throw new BusinessSecurityException(
                            "scenario operation has too many parameters");
        }
        var selected = new OperationSelector().select("business-scenarios", document);
        if (selected.isEmpty() || selected.size() > properties.maxOperations())
            throw new BusinessSecurityException(
                    "scenario document operation count outside supported range");
        for (var operation : selected) {
            structuralPath(operation.pathTemplate());
            BusinessSecuritySuite.method(operation.method());
            operations.put(operation.key(), operation);
            operationIds
                    .computeIfAbsent(operation.operationId(), ignored -> new ArrayList<>())
                    .add(operation.key());
        }
        for (var operation : operations.values()) {
            var responses = operation.operation().getResponses();
            if (responses == null) continue;
            if (responses.size() > properties.maxLinks())
                throw new BusinessSecurityException("scenario response metadata exceeds max-links");
            for (var response : new TreeMap<>(responses).entrySet()) {
                var value = response.getValue();
                if (value == null || value.getLinks() == null) continue;
                if (value.getLinks().size() > properties.maxLinks())
                    throw new BusinessSecurityException("scenario graph exceeds max-links");
                for (var declared : new TreeMap<>(value.getLinks()).values())
                    explicit(document, operation, response.getKey(), declared);
            }
        }
        if (properties.pathHints()) hints();
    }

    private void explicit(
            OpenAPI document, ExplorableOperation from, String response, Link declared) {
        String id = "link" + links.size();
        Link link = resolveLink(document, declared);
        String target = link == null ? null : target(link);
        List<Binding> bindings = new ArrayList<>();
        String reason =
                link == null
                        ? "unsupported-or-cyclic-link-reference"
                        : target == null ? "unresolved-or-ambiguous-operation" : null;
        if (!response.matches("2[0-9][0-9]")) reason = "link-response-is-not-an-explicit-success";
        if (link != null && link.getServer() != null) reason = "link-server-override-not-supported";
        boolean blocked = reason != null;
        if (target != null && link != null) {
            if (link.getParameters() != null) {
                if (link.getParameters().size() > 200)
                    throw new BusinessSecurityException("too many link parameters");
                for (var entry : new TreeMap<>(link.getParameters()).entrySet()) {
                    Binding binding =
                            parameter(operations.get(target), entry.getKey(), entry.getValue());
                    if (binding == null) {
                        if (reason == null) reason = "link-parameter-needs-project-binding";
                    } else bindings.add(binding);
                }
            }
            if (link.getRequestBody() != null) {
                Binding body = expression("body", "body", link.getRequestBody());
                if (body == null && reason == null) reason = "link-body-needs-project-binding";
                else if (body != null) bindings.add(body);
            }
        }
        add(
                new BusinessScenarioReport.Link(
                        id,
                        from.key(),
                        target,
                        "openapi-link",
                        response.matches("[1-5][0-9][0-9]") ? response : "unspecified",
                        blocked ? "blocked" : reason == null ? "resolved" : "needs_review",
                        reason,
                        bindings));
    }

    private Link resolveLink(OpenAPI document, Link link) {
        Set<String> seen = new HashSet<>();
        for (int depth = 0; link != null && link.get$ref() != null && depth < 16; depth++) {
            String ref = link.get$ref();
            if (!ref.startsWith("#/components/links/")
                    || !seen.add(ref)
                    || document.getComponents() == null
                    || document.getComponents().getLinks() == null) return null;
            String name = decode(ref.substring("#/components/links/".length()));
            link = document.getComponents().getLinks().get(name);
        }
        return link != null && link.get$ref() == null ? link : null;
    }

    private String target(Link link) {
        if (link.getOperationId() != null && link.getOperationRef() != null) return null;
        if (link.getOperationId() != null) {
            var keys = operationIds.getOrDefault(link.getOperationId(), List.of());
            return keys.size() == 1 ? keys.get(0) : null;
        }
        String ref = link.getOperationRef();
        if (ref == null || !ref.startsWith("#/paths/")) return null;
        int slash = ref.lastIndexOf('/');
        if (slash <= "#/paths/".length()) return null;
        String path = decode(ref.substring("#/paths/".length(), slash));
        String key = ref.substring(slash + 1).toUpperCase(Locale.ROOT) + " " + path;
        return operations.containsKey(key) ? key : null;
    }

    private Binding parameter(ExplorableOperation target, String name, Object value) {
        String location = null;
        String parameter = name;
        if (name.startsWith("path.") || name.startsWith("query.")) {
            int dot = name.indexOf('.');
            location = name.substring(0, dot);
            parameter = name.substring(dot + 1);
        }
        final String requested = parameter;
        final String in = location;
        var candidates =
                target.parameters().stream()
                        .filter(
                                p ->
                                        requested.equals(p.getName())
                                                && Set.of("path", "query")
                                                        .contains(
                                                                p.getIn() == null ? "" : p.getIn())
                                                && (in == null || in.equals(p.getIn())))
                        .toList();
        if (candidates.size() != 1 || !identifier(parameter)) return null;
        return expression(candidates.get(0).getIn(), parameter, value);
    }

    private Binding expression(String location, String parameter, Object value) {
        if (!(value instanceof String expression)) return null;
        if (expression.startsWith("$response.body#")) {
            String pointer = expression.substring("$response.body#".length());
            if (!safePointer(pointer)) return null;
            return new Binding(location, parameter, "response-body", pointer);
        }
        for (String in : List.of("path", "query")) {
            String prefix = "$request." + in + ".";
            if (expression.startsWith(prefix) && identifier(expression.substring(prefix.length())))
                return new Binding(
                        location,
                        parameter,
                        "request-" + in,
                        expression.substring(prefix.length()));
        }
        return null;
    }

    private void hints() {
        Map<String, ExplorableOperation> reads = new HashMap<>();
        operations.values().stream()
                .filter(op -> op.method().equals("GET"))
                .forEach(op -> reads.put(op.pathTemplate(), op));
        for (var op : operations.values()) {
            if (op.method().equals("GET")) continue;
            var read =
                    reads.getOrDefault(
                            op.pathTemplate(),
                            reads.get(op.pathTemplate().replaceFirst("/[^/]+$", "")));
            if (read == null) continue;
            hint(read, op);
            hint(op, read);
        }
    }

    private void hint(ExplorableOperation from, ExplorableOperation to) {
        if (links.stream()
                .anyMatch(link -> from.key().equals(link.from()) && to.key().equals(link.to())))
            return;
        List<Binding> bindings = new ArrayList<>();
        for (var p : to.parameters())
            if (identifier(p.getName())
                    && Set.of("path", "query").contains(p.getIn() == null ? "" : p.getIn())
                    && from.parameters().stream()
                            .anyMatch(
                                    source ->
                                            p.getName().equals(source.getName())
                                                    && p.getIn().equals(source.getIn())))
                bindings.add(
                        new Binding(p.getIn(), p.getName(), "request-" + p.getIn(), p.getName()));
        add(
                new BusinessScenarioReport.Link(
                        "link" + links.size(),
                        from.key(),
                        to.key(),
                        "path-hint",
                        null,
                        "needs_review",
                        "same-entity-path-is-only-a-relationship-hint",
                        bindings));
    }

    private void add(BusinessScenarioReport.Link link) {
        if (links.size() >= properties.maxLinks())
            throw new BusinessSecurityException("scenario graph exceeds max-links");
        links.add(link);
    }

    private static String decode(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }

    static boolean identifier(String value) {
        return value != null && value.matches("[a-zA-Z][a-zA-Z0-9_]{0,39}");
    }

    static boolean safePointer(String pointer) {
        if (pointer == null
                || pointer.length() > 512
                || !pointer.isEmpty() && !pointer.startsWith("/")) return false;
        for (String token : pointer.split("/"))
            if (!token.isEmpty() && !identifier(decode(token)) && !token.matches("[0-9]{1,6}"))
                return false;
        return true;
    }

    static void structuralPath(String path) {
        SecurityTargetPolicy.requireRelative(path);
        if (path.length() > 512)
            throw new BusinessSecurityException("scenario structural path is too long");
        for (String segment : path.split("/"))
            if (!segment.isEmpty()
                    && !segment.matches(
                            "[a-zA-Z][a-zA-Z_-]{0,39}|v[0-9]{1,3}|\\{[a-zA-Z][a-zA-Z0-9_]{0,39}}"))
                throw new BusinessSecurityException(
                        "scenario document needs structural paths without literal identifiers");
    }
}
