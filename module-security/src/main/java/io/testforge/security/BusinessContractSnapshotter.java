package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.testforge.api.explorer.ExplorableOperation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Bounded schema/type normalization. Examples, defaults, enum contents and constraints' values are
 * omitted.
 */
final class BusinessContractSnapshotter {
    private final BusinessPolicyProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();
    private int nodes;
    private OpenAPI document;
    private final Map<String, JsonNode> definitions = new TreeMap<>();

    BusinessContractSnapshotter(BusinessPolicyProperties properties) {
        this.properties = properties;
    }

    BusinessPolicyReview snapshot(OpenAPI document, BusinessSecuritySuite suite) {
        this.document = document;
        nodes = 0;
        definitions.clear();
        var graph =
                new BusinessScenarioGraph(
                        document,
                        new BusinessScenarioProperties(
                                properties.maxOperations(),
                                Math.min(10000, properties.maxSchemaNodes()),
                                null,
                                null,
                                false));
        Map<String, List<String>> dependencies = new TreeMap<>();
        Set<String> used = new TreeSet<>();
        for (var test : suite.cases()) {
            Set<String> keys = new TreeSet<>();
            for (var step : steps(test))
                keys.add(
                        step.request().method().toUpperCase(java.util.Locale.ROOT)
                                + " "
                                + step.request().pathTemplate());
            keys.add(test.method() + " " + test.path());
            used.addAll(keys);
            dependencies.put(test.id(), List.copyOf(keys));
        }
        Map<String, JsonNode> operations = new TreeMap<>();
        for (String key : used) {
            var operation = graph.operations.get(key);
            if (operation == null) continue;
            ObjectNode shape = operation(operation);
            // Ignore graph IDs/order: unrelated links must not invalidate a case.
            var links = mapper.createArrayNode();
            graph.links.stream()
                    .filter(link -> link.from().equals(key))
                    .map(
                            link -> {
                                ObjectNode normalized = mapper.valueToTree(link);
                                normalized.remove("id");
                                return normalized;
                            })
                    .sorted(java.util.Comparator.comparing(JsonNode::toString))
                    .forEach(links::add);
            shape.set("links", links);
            operations.put(key, shape);
        }
        Map<String, JsonNode> cases = new TreeMap<>();
        for (var test : suite.cases()) cases.put(test.id(), policyCase(test));
        return new BusinessPolicyReview(
                1,
                suite.id(),
                suite.version(),
                List.copyOf(graph.operations.keySet()),
                operations,
                scope(suite),
                cases,
                dependencies);
    }

    private ObjectNode operation(ExplorableOperation operation) {
        ObjectNode result = mapper.createObjectNode();
        Set<String> refs = new TreeSet<>();
        var params = result.putArray("parameters");
        operation.parameters().stream()
                .sorted(java.util.Comparator.comparing(p -> p.getIn() + ":" + p.getName()))
                .forEach(
                        p -> {
                            metadata(p.getName());
                            if (!Set.of("path", "query", "header", "cookie")
                                    .contains(p.getIn() == null ? "" : p.getIn()))
                                throw new BusinessSecurityException(
                                        "policy parameter location is unsupported");
                            ObjectNode node =
                                    params.addObject()
                                            .put("name", p.getName())
                                            .put("in", p.getIn())
                                            .put("required", Boolean.TRUE.equals(p.getRequired()));
                            node.set(
                                    "schema",
                                    schema(p.getSchema(), refs, new IdentityHashMap<>(), 0));
                        });
        var body = operation.operation().getRequestBody();
        if (body != null) {
            var node =
                    result.putObject("request")
                            .put("required", Boolean.TRUE.equals(body.getRequired()));
            if (body.get$ref() != null)
                throw new BusinessSecurityException(
                        "policy needs resolved request-body references");
            content(node.putObject("content"), body.getContent(), refs);
        }
        var responses = result.putObject("responses");
        if (operation.operation().getResponses() != null)
            for (var response : new TreeMap<>(operation.operation().getResponses()).entrySet()) {
                if (!response.getKey().matches("[1-5]([0-9]{2}|XX)|default"))
                    throw new BusinessSecurityException("policy response code is unsupported");
                if (response.getValue().get$ref() != null)
                    throw new BusinessSecurityException(
                            "policy needs resolved response references");
                content(
                        responses.putObject(response.getKey()),
                        response.getValue().getContent(),
                        refs);
            }
        List<SecurityRequirement> security =
                operation.operation().getSecurity() == null
                        ? document.getSecurity()
                        : operation.operation().getSecurity();
        var requirements = result.putArray("security");
        if (security != null)
            for (var requirement : security) {
                tick();
                if (requirement.size() > properties.maxSchemaNodes())
                    throw new BusinessSecurityException("policy security metadata exceeds bounds");
                ObjectNode item = mapper.createObjectNode();
                for (var entry : new TreeMap<>(requirement).entrySet()) {
                    tick();
                    metadata(entry.getKey());
                    if (entry.getValue().size() > properties.maxSchemaNodes())
                        throw new BusinessSecurityException("policy security scopes exceed bounds");
                    var scopes = item.putArray(entry.getKey());
                    for (String scope : entry.getValue().stream().sorted().toList()) {
                        tick();
                        metadata(scope);
                        scopes.add(scope);
                    }
                }
                requirements.add(item);
            }
        ObjectNode schemes = result.putObject("securitySchemes");
        Set<String> names = new TreeSet<>();
        if (security != null) security.forEach(requirement -> names.addAll(requirement.keySet()));
        for (String name : names) {
            var source =
                    document.getComponents() == null
                                    || document.getComponents().getSecuritySchemes() == null
                            ? null
                            : document.getComponents().getSecuritySchemes().get(name);
            ObjectNode scheme = schemes.putObject(name);
            if (source == null) {
                scheme.put("unknown", true);
                continue;
            }
            if (source.get$ref() != null)
                throw new BusinessSecurityException(
                        "policy needs resolved security-scheme references");
            if (source.getType() != null) scheme.put("type", source.getType().name());
            if (source.getIn() != null) scheme.put("in", source.getIn().name());
            if (source.getName() != null) {
                metadata(source.getName());
                scheme.put("name", source.getName());
            }
            if (source.getScheme() != null)
                scheme.put(
                        "scheme",
                        Set.of("basic", "bearer")
                                        .contains(
                                                source.getScheme()
                                                        .toLowerCase(java.util.Locale.ROOT))
                                ? source.getScheme().toLowerCase(java.util.Locale.ROOT)
                                : "custom");
            if (source.getFlows() != null) {
                var flows = source.getFlows();
                Map<String, io.swagger.v3.oas.models.security.OAuthFlow> declared = new TreeMap<>();
                if (flows.getImplicit() != null) declared.put("implicit", flows.getImplicit());
                if (flows.getPassword() != null) declared.put("password", flows.getPassword());
                if (flows.getClientCredentials() != null)
                    declared.put("clientCredentials", flows.getClientCredentials());
                if (flows.getAuthorizationCode() != null)
                    declared.put("authorizationCode", flows.getAuthorizationCode());
                var normalized = scheme.putObject("flows");
                declared.forEach(
                        (flow, value) -> {
                            var scopes = normalized.putArray(flow);
                            if (value.getScopes() != null)
                                value.getScopes().keySet().stream()
                                        .sorted()
                                        .forEach(
                                                scope -> {
                                                    tick();
                                                    metadata(scope);
                                                    scopes.add(scope);
                                                });
                        });
            }
        }
        ObjectNode components = result.putObject("components");
        var queue = new ArrayDeque<>(refs);
        Set<String> visited = new TreeSet<>();
        while (!queue.isEmpty()) {
            String name = queue.remove();
            if (!visited.add(name)) continue;
            tick();
            JsonNode normalized = definitions.get(name);
            Set<String> nested = new TreeSet<>();
            if (normalized == null) {
                Schema<?> source =
                        document.getComponents() == null
                                        || document.getComponents().getSchemas() == null
                                ? null
                                : document.getComponents().getSchemas().get(name);
                if (source == null)
                    throw new BusinessSecurityException("policy schema reference is unresolved");
                normalized = schema(source, nested, new IdentityHashMap<>(), 0);
                definitions.put(name, normalized);
            } else collectRefs(normalized, nested);
            components.set(name, normalized);
            queue.addAll(nested);
        }
        return result;
    }

    private void content(
            ObjectNode target, io.swagger.v3.oas.models.media.Content content, Set<String> refs) {
        if (content == null) return;
        if (content.size() > 100)
            throw new BusinessSecurityException("policy media count exceeds bounds");
        for (var entry : new TreeMap<>(content).entrySet()) {
            if (!entry.getKey().matches("[a-zA-Z0-9.+_-]{1,80}/[a-zA-Z0-9.+*_-]{1,80}"))
                throw new BusinessSecurityException("policy media type is unsupported");
            target.set(
                    entry.getKey(),
                    schema(entry.getValue().getSchema(), refs, new IdentityHashMap<>(), 0));
        }
    }

    @SuppressWarnings("rawtypes")
    private JsonNode schema(
            Schema<?> source,
            Set<String> refs,
            IdentityHashMap<Schema<?>, Boolean> active,
            int depth) {
        tick();
        if (depth > properties.maxDepth())
            throw new BusinessSecurityException("policy schema depth exceeds bounds");
        ObjectNode node = mapper.createObjectNode();
        if (source == null) return node.put("unknown", true);
        if (active.put(source, true) != null) return node.put("recursive", true);
        try {
            if (source.get$ref() != null) {
                String prefix = "#/components/schemas/";
                if (!source.get$ref().startsWith(prefix))
                    throw new BusinessSecurityException("policy schema references must be local");
                String name = source.get$ref().substring(prefix.length());
                metadata(name);
                refs.add(name);
                node.put("ref", name);
            }
            if (source.getType() != null) node.put("type", type(source.getType()));
            if (source.getTypes() != null) {
                var types = node.putArray("types");
                source.getTypes().stream().sorted().forEach(t -> types.add(type(t)));
            }
            if (source.getFormat() != null)
                node.put(
                        "format",
                        Set.of(
                                                "int32",
                                                "int64",
                                                "float",
                                                "double",
                                                "byte",
                                                "binary",
                                                "date",
                                                "date-time",
                                                "password",
                                                "email",
                                                "uuid",
                                                "uri",
                                                "hostname",
                                                "ipv4",
                                                "ipv6")
                                        .contains(source.getFormat())
                                ? source.getFormat()
                                : "custom");
            node.put("nullable", Boolean.TRUE.equals(source.getNullable()))
                    .put("readOnly", Boolean.TRUE.equals(source.getReadOnly()))
                    .put("writeOnly", Boolean.TRUE.equals(source.getWriteOnly()));
            node.put("hasPattern", source.getPattern() != null)
                    .put("hasMinimum", source.getMinimum() != null)
                    .put("hasMaximum", source.getMaximum() != null);
            if (source.getEnum() != null) node.put("enumSize", source.getEnum().size());
            if (source.getRequired() != null) {
                if (source.getRequired().size() > properties.maxSchemaNodes())
                    throw new BusinessSecurityException("policy required fields exceed bounds");
                var required = node.putArray("required");
                source.getRequired().stream()
                        .sorted()
                        .forEach(
                                name -> {
                                    tick();
                                    metadata(name);
                                    required.add(name);
                                });
            }
            if (source.getProperties() != null) {
                if (source.getProperties().size() > properties.maxSchemaNodes())
                    throw new BusinessSecurityException("policy schema properties exceed bounds");
                ObjectNode fields = node.putObject("properties");
                Map<String, Schema> properties = source.getProperties();
                for (var field : new TreeMap<>(properties).entrySet()) {
                    metadata(field.getKey());
                    fields.set(field.getKey(), schema(field.getValue(), refs, active, depth + 1));
                }
            }
            if (source.getItems() != null)
                node.set("items", schema(source.getItems(), refs, active, depth + 1));
            if (source.getAdditionalProperties() instanceof Boolean allowed)
                node.put("additionalAllowed", allowed);
            else if (source.getAdditionalProperties() instanceof Schema additional)
                node.set("additionalSchema", schema(additional, refs, active, depth + 1));
            for (String name : List.of("allOf", "anyOf", "oneOf")) {
                List<Schema> alternatives =
                        switch (name) {
                            case "allOf" -> source.getAllOf();
                            case "anyOf" -> source.getAnyOf();
                            default -> source.getOneOf();
                        };
                if (alternatives != null) {
                    var list = node.putArray(name);
                    for (Schema alternative : alternatives)
                        list.add(schema(alternative, refs, active, depth + 1));
                }
            }
            return node;
        } finally {
            active.remove(source);
        }
    }

    private void collectRefs(JsonNode node, Set<String> refs) {
        if (node.path("ref").isTextual()) refs.add(node.path("ref").asText());
        node.path("properties").forEach(child -> collectRefs(child, refs));
        for (String field : List.of("items", "additionalSchema"))
            if (node.has(field)) collectRefs(node.get(field), refs);
        for (String field : List.of("allOf", "anyOf", "oneOf"))
            node.path(field).forEach(child -> collectRefs(child, refs));
    }

    private void tick() {
        if (++nodes > properties.maxSchemaNodes())
            throw new BusinessSecurityException("policy snapshot exceeds schema-node budget");
    }

    private static String type(String type) {
        return Set.of("object", "array", "string", "integer", "number", "boolean", "null")
                        .contains(type)
                ? type
                : "custom";
    }

    static void metadata(String value) {
        if (value == null || !value.matches("[a-zA-Z_][a-zA-Z0-9_.:-]{0,63}"))
            throw new BusinessSecurityException(
                    "policy metadata needs non-sensitive structural names");
    }

    static void structuralPointer(String pointer) {
        if (pointer.isEmpty()) return;
        for (String token : pointer.substring(1).split("/", -1)) {
            String field = token.replace("~1", "/").replace("~0", "~");
            if (!field.matches("[0-9]{1,6}")) metadata(field);
        }
    }

    static List<BusinessSecuritySuite.Step> steps(BusinessSecuritySuite.Case test) {
        List<BusinessSecuritySuite.Step> result = new ArrayList<>(test.steps());
        result.addAll(test.cleanup());
        return result;
    }

    private JsonNode scope(BusinessSecuritySuite suite) {
        for (int index = 0;
                index
                        < suite.roles().size()
                                + suite.checks().size()
                                + suite.tenants().size()
                                + suite.exclusions().size();
                index++) tick();
        ObjectNode node = mapper.createObjectNode();
        node.set("roles", mapper.valueToTree(suite.roles().stream().sorted().toList()));
        node.set("checks", mapper.valueToTree(suite.checks().stream().sorted().toList()));
        node.set("tenants", mapper.valueToTree(new TreeMap<>(suite.tenants())));
        node.set(
                "exclusions",
                mapper.valueToTree(
                        suite.exclusions().stream()
                                .sorted(java.util.Comparator.comparing(Object::toString))
                                .toList()));
        return node;
    }

    private JsonNode policyCase(BusinessSecuritySuite.Case test) {
        ObjectNode node =
                mapper.createObjectNode()
                        .put("kind", test.kind().name())
                        .put("method", test.method())
                        .put("path", test.path())
                        .put("role", test.role());
        for (String section : List.of("steps", "cleanup")) {
            var list = node.putArray(section);
            for (var step : section.equals("steps") ? test.steps() : test.cleanup()) {
                tick();
                step.captures()
                        .values()
                        .forEach(
                                pointer -> {
                                    tick();
                                    structuralPointer(pointer);
                                });
                var item =
                        list.addObject()
                                .put("id", step.id())
                                .put("phase", step.phase().name())
                                .put("role", step.role())
                                .put(
                                        "operation",
                                        step.request().method().toUpperCase(java.util.Locale.ROOT)
                                                + " "
                                                + step.request().pathTemplate());
                item.set(
                        "statuses", mapper.valueToTree(step.statuses().stream().sorted().toList()));
                item.set("captures", mapper.valueToTree(new TreeMap<>(step.captures())));
                item.set(
                        "path",
                        literalShape(mapper.valueToTree(step.request().pathParameters()), 0));
                item.set(
                        "query",
                        literalShape(mapper.valueToTree(step.request().queryParameters()), 0));
                if (step.request().body() != null)
                    try {
                        item.set("body", literalShape(mapper.readTree(step.request().body()), 0));
                    } catch (Exception e) {
                        throw new BusinessSecurityException(
                                "policy body template is not bounded JSON");
                    }
                var assertions = item.putArray("assertions");
                for (var assertion : step.assertions()) {
                    tick();
                    structuralPointer(assertion.pointer());
                    var a =
                            assertions
                                    .addObject()
                                    .put("pointer", assertion.pointer())
                                    .put("match", assertion.match().name());
                    if (assertion.expected() != null)
                        a.set("expected", literalShape(assertion.expected(), 0));
                }
            }
        }
        return node;
    }

    private JsonNode literalShape(JsonNode value, int depth) {
        tick();
        if (value == null || depth > properties.maxDepth())
            throw new BusinessSecurityException("policy literal shape exceeds bounds");
        if (value.isObject()) {
            if (value.size() > properties.maxSchemaNodes())
                throw new BusinessSecurityException("policy object shape exceeds bounds");
            ObjectNode object = mapper.createObjectNode();
            value.properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(
                            field -> {
                                metadata(field.getKey());
                                object.set(
                                        field.getKey(), literalShape(field.getValue(), depth + 1));
                            });
            return object;
        }
        if (value.isArray()) {
            var array = mapper.createArrayNode();
            value.forEach(child -> array.add(literalShape(child, depth + 1)));
            return array;
        }
        if (value.isTextual() && value.textValue().matches("\\$\\{[a-zA-Z][a-zA-Z0-9_-]{0,63}}"))
            return mapper.createObjectNode()
                    .put("binding", value.textValue().substring(2, value.textValue().length() - 1));
        return mapper.createObjectNode().put("literalType", value.getNodeType().name());
    }
}
