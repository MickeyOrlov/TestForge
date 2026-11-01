package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.artifact.ArtifactSink;
import io.testforge.security.BusinessScenarioReport.Draft;
import io.testforge.security.BusinessScenarioReport.Need;
import io.testforge.security.BusinessSecuritySuite.Kind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Offline graph and editable, intentionally non-executable policy drafts. Never measures security.
 */
public class BusinessScenarioGenerator {
    private final BusinessScenarioProperties properties;
    private final SecurityProperties reports;
    private final ArtifactSink sink;
    private final ObjectMapper mapper = new ObjectMapper();

    public BusinessScenarioGenerator(
            BusinessScenarioProperties properties, SecurityProperties reports, ArtifactSink sink) {
        this.properties = properties;
        this.reports = reports;
        this.sink = sink;
    }

    public BusinessScenarioReport generate(OpenAPI document, List<BusinessScenarioSeed> seeds) {
        try {
            if (seeds == null || seeds.size() > properties.maxDrafts())
                throw new BusinessSecurityException("scenario seeds exceed max-drafts");
            Set<String> ids = new HashSet<>();
            for (var seed : seeds)
                if (!ids.add(seed.id()))
                    throw new BusinessSecurityException("duplicate scenario seed id");
            var graph = new BusinessScenarioGraph(document, properties);
            List<Draft> drafts = new ArrayList<>();
            for (var seed : seeds) drafts.add(draft(graph, seed));
            var report =
                    new BusinessScenarioReport(
                            1,
                            graph.operations.keySet().stream()
                                    .map(BusinessScenarioReport.Operation::new)
                                    .toList(),
                            graph.links,
                            drafts);
            write(report);
            return report;
        } catch (RuntimeException e) {
            if (e instanceof BusinessSecurityException safe) throw safe;
            throw new BusinessSecurityException("scenario document or output is unsupported");
        }
    }

    private Draft draft(BusinessScenarioGraph graph, BusinessScenarioSeed seed) {
        List<Need> needs = new ArrayList<>();
        if (!graph.operations.containsKey(seed.setup())
                || !graph.operations.containsKey(seed.probe())
                || seed.via().size() > properties.maxChain())
            return blocked(seed, "seed-operation-or-waypoint-not-supported");
        List<BusinessScenarioReport.Link> chain = new ArrayList<>();
        String from = seed.setup();
        List<String> targets = new ArrayList<>(seed.via());
        targets.add(seed.probe());
        for (String target : targets) {
            if (!graph.operations.containsKey(target))
                return blocked(seed, "waypoint-operation-not-found");
            var leg = path(graph, from, target);
            if (leg == null)
                return blocked(seed, "no-unique-bounded-operation-chain-use-waypoints");
            chain.addAll(leg);
            from = target;
        }
        if (chain.size() + 1 > properties.maxChain())
            return blocked(seed, "chain-exceeds-max-chain");
        ExplorableOperation probe = graph.operations.get(seed.probe());
        boolean writeProbe = !read(probe.method());
        if (!writeProbe
                && Set.of(Kind.PROPERTY_WRITE, Kind.TRANSITION, Kind.REPLAY).contains(seed.kind()))
            return blocked(seed, "selected-kind-needs-write-operation");
        if ((seed.kind() == Kind.OBJECT_ACCESS || seed.kind() == Kind.TENANT_ACCESS)
                && seed.ownerRole().equals(seed.actorRole()))
            return blocked(seed, "owner-and-actor-must-be-distinct");
        List<ExplorableOperation> operations = new ArrayList<>();
        operations.add(graph.operations.get(seed.setup()));
        for (var edge : chain) operations.add(graph.operations.get(edge.to()));
        if (operations.stream().anyMatch(this::unsupportedParameters))
            return blocked(seed, "required-header-cookie-or-unsupported-parameter");
        boolean writes = operations.stream().anyMatch(op -> !read(op.method()));
        List<ObjectNode> prepared = new ArrayList<>();
        for (int index = 0; index < operations.size(); index++) {
            ObjectNode step =
                    step("step" + index, "CONTROL", seed.ownerRole(), operations.get(index));
            if (index > 0) apply(chain.get(index - 1), prepared.get(index - 1), step, needs);
            prepared.add(step);
        }
        ObjectNode primary = prepared.remove(prepared.size() - 1);
        primary.put("id", "probe").put("phase", "PROBE").put("role", seed.actorRole());
        if (seed.kind() == Kind.PROPERTY_WRITE && primary.path("request").path("body").isNull())
            ((ObjectNode) primary.get("request"))
                    .put("body", "\"${input_probe_body}\"")
                    .put("contentType", "application/json");
        ObjectNode controlRequest = readRequest(graph, probe, (ObjectNode) primary.get("request"));
        if (controlRequest == null)
            return blocked(seed, "matching-owner-read-required-for-controls-and-verification");
        ArrayNode steps = mapper.createArrayNode();
        prepared.forEach(steps::add);
        if (seed.kind() == Kind.REPLAY) {
            ObjectNode first = primary.deepCopy();
            first.put("id", "first-success").put("phase", "CONTROL");
            steps.add(first);
        }
        ObjectNode control =
                emptyStep("owner-control", "CONTROL", seed.ownerRole(), controlRequest);
        ((ObjectNode) control.get("captures")).put("before", "");
        needs.add(
                new Need(
                        "owner-control",
                        "relationship",
                        null,
                        "review-matching-entity-read-and-shared-bindings"));
        steps.add(control);
        steps.add(primary);
        if (writes)
            steps.add(emptyStep("verify", "VERIFY", seed.ownerRole(), controlRequest.deepCopy()));
        ArrayNode cleanup = mapper.createArrayNode();
        if (seed.cleanup() != null) {
            var cleanupOp = graph.operations.get(seed.cleanup());
            if (cleanupOp == null
                    || !cleanupOp.method().equals("DELETE")
                    || unsupportedParameters(cleanupOp)
                    || !cleanupOp
                            .pathTemplate()
                            .equals(controlRequest.path("pathTemplate").asText()))
                return blocked(seed, "cleanup-must-be-an-explicit-matching-delete-operation");
            ObjectNode cleanupRequest = controlRequest.deepCopy();
            cleanupRequest.put("method", "DELETE");
            cleanup.add(emptyStep("cleanup", "CONTROL", seed.ownerRole(), cleanupRequest));
            needs.add(
                    new Need(
                            "cleanup",
                            "cleanup-policy",
                            null,
                            "confirm-disposable-fixture-and-delete-policy"));
        } else if (writes)
            return blocked(seed, "generated-write-chain-requires-explicit-cleanup-operation");
        ObjectNode test = mapper.createObjectNode();
        test.put("id", seed.id())
                .put("kind", seed.kind().name())
                .put("method", probe.method())
                .put("path", probe.pathTemplate())
                .put("role", seed.actorRole());
        test.set("steps", steps);
        test.set("cleanup", cleanup);
        ObjectNode suite = mapper.createObjectNode();
        suite.put("id", seed.id()).put("version", seed.version());
        suite.putArray("roles").add(seed.actorRole());
        suite.putArray("checks").add(seed.kind().name());
        suite.putArray("cases").add(test);
        suite.putArray("exclusions");
        suite.putObject("tenants");
        for (var step : steps) reviewNeeds((ObjectNode) step, needs);
        for (var step : cleanup) reviewNeeds((ObjectNode) step, needs);
        if (seed.kind() == Kind.TENANT_ACCESS)
            needs.add(
                    new Need(
                            "suite",
                            "tenant-policy",
                            null,
                            "declare-distinct-owner-and-actor-tenant-aliases"));
        if (seed.kind() == Kind.PROPERTY_READ)
            needs.add(new Need("probe", "assertions", null, "declare-absent-response-fields"));
        if (writes)
            needs.add(
                    new Need(
                            "verify",
                            "assertions",
                            "before",
                            "declare-persisted-state-invariants-after-probe"));
        needs.add(
                new Need(
                        "suite",
                        "coverage",
                        null,
                        "review-all-document-cells-and-explicit-exclusions"));
        List<String> route = new ArrayList<>();
        route.add(seed.setup());
        chain.forEach(edge -> route.add(edge.to()));
        return new Draft(seed.id(), "needs_review", route, needs, suite);
    }

    /**
     * Breadth-first search, with counts capped at two: ambiguous shortest paths are not guessed.
     */
    private List<BusinessScenarioReport.Link> path(
            BusinessScenarioGraph graph, String from, String target) {
        if (from.equals(target)) return List.of();
        Map<String, Integer> distance = new HashMap<>(), count = new HashMap<>();
        Map<String, BusinessScenarioReport.Link> previous = new HashMap<>();
        Map<String, List<BusinessScenarioReport.Link>> outgoing = new TreeMap<>();
        for (var edge : graph.links)
            if (edge.to() != null && !edge.status().equals("blocked"))
                outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge);
        var queue = new ArrayDeque<String>();
        queue.add(from);
        distance.put(from, 0);
        count.put(from, 1);
        while (!queue.isEmpty()) {
            String current = queue.remove();
            int next = distance.get(current) + 1;
            if (next >= properties.maxChain()) continue;
            for (var edge : outgoing.getOrDefault(current, List.of())) {
                if (!distance.containsKey(edge.to())) {
                    distance.put(edge.to(), next);
                    count.put(edge.to(), count.get(current));
                    previous.put(edge.to(), edge);
                    queue.add(edge.to());
                } else if (distance.get(edge.to()) == next)
                    count.put(edge.to(), Math.min(2, count.get(edge.to()) + count.get(current)));
            }
        }
        if (!distance.containsKey(target) || count.get(target) != 1) return null;
        List<BusinessScenarioReport.Link> result = new ArrayList<>();
        String current = target;
        while (!current.equals(from)) {
            var edge = previous.get(current);
            result.add(0, edge);
            current = edge.from();
        }
        return result;
    }

    private ObjectNode step(String id, String phase, String role, ExplorableOperation operation) {
        ObjectNode request = mapper.createObjectNode();
        request.put("method", operation.method()).put("pathTemplate", operation.pathTemplate());
        ObjectNode path = request.putObject("pathParameters"),
                query = request.putObject("queryParameters");
        var matcher =
                Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]{0,39})}")
                        .matcher(operation.pathTemplate());
        while (matcher.find())
            path.put(matcher.group(1), "${input_" + id + "_path_" + matcher.group(1) + "}");
        for (var p : operation.parameters())
            if (Boolean.TRUE.equals(p.getRequired()) && "query".equals(p.getIn()))
                query.put(p.getName(), "${input_" + id + "_query_" + p.getName() + "}");
        if (!read(operation.method())
                && (operation.operation().getRequestBody() != null
                        || operation.method().equals("PATCH")
                        || operation.method().equals("PUT"))) {
            request.put("body", "\"${input_" + id + "_body}\"")
                    .put("contentType", "application/json");
        } else {
            request.putNull("body");
            request.putNull("contentType");
        }
        return emptyStep(id, phase, role, request);
    }

    private ObjectNode emptyStep(String id, String phase, String role, ObjectNode request) {
        ObjectNode step = mapper.createObjectNode();
        step.put("id", id).put("phase", phase).put("role", role);
        step.set("request", request);
        step.putArray("statuses");
        step.putArray("assertions");
        step.putObject("captures");
        return step;
    }

    private void apply(
            BusinessScenarioReport.Link edge,
            ObjectNode source,
            ObjectNode target,
            List<Need> needs) {
        if (edge.response() != null)
            needs.add(
                    new Need(
                            source.path("id").asText(),
                            "link-response",
                            null,
                            "review-source-status-" + edge.response()));
        if (!edge.status().equals("resolved"))
            needs.add(new Need(target.path("id").asText(), "relationship", null, edge.reason()));
        ObjectNode request = (ObjectNode) target.get("request");
        int index = 0;
        for (var binding : edge.bindings()) {
            String value;
            if (binding.source().equals("response-body")) {
                String name = "capture_" + source.path("id").asText() + "_" + index++;
                ((ObjectNode) source.get("captures")).put(name, binding.pointer());
                value = "${" + name + "}";
            } else {
                String map =
                        binding.source().equals("request-path")
                                ? "pathParameters"
                                : "queryParameters";
                JsonNode prior = source.path("request").path(map).get(binding.pointer());
                if (prior == null || !prior.isTextual()) {
                    needs.add(
                            new Need(
                                    target.path("id").asText(),
                                    "binding",
                                    null,
                                    "link-source-request-value-not-available"));
                    continue;
                }
                value = prior.asText();
            }
            if (binding.location().equals("body"))
                request.put("body", "\"" + value + "\"").put("contentType", "application/json");
            else
                ((ObjectNode)
                                request.get(
                                        binding.location().equals("path")
                                                ? "pathParameters"
                                                : "queryParameters"))
                        .put(binding.parameter(), value);
        }
    }

    private ObjectNode readRequest(
            BusinessScenarioGraph graph, ExplorableOperation probe, ObjectNode primary) {
        String path = probe.pathTemplate();
        String parent = path.replaceFirst("/[^/]+$", "");
        ExplorableOperation read = graph.operations.get("GET " + path);
        if (read == null) read = graph.operations.get("GET " + parent);
        if (read == null || unsupportedParameters(read)) return null;
        ObjectNode request = primary.deepCopy();
        request.put("method", "GET").put("pathTemplate", read.pathTemplate());
        request.putNull("body");
        request.putNull("contentType");
        // Exact shared bindings match the existing business executor's same-fixture guard.
        var required =
                read.parameters().stream()
                        .filter(
                                p ->
                                        Boolean.TRUE.equals(p.getRequired())
                                                && "query".equals(p.getIn()))
                        .toList();
        if (required.stream().anyMatch(p -> !request.path("queryParameters").has(p.getName())))
            return null;
        var matcher = Pattern.compile("\\{([^}]+)}").matcher(read.pathTemplate());
        while (matcher.find())
            if (!request.path("pathParameters").has(matcher.group(1))) return null;
        return request;
    }

    private boolean unsupportedParameters(ExplorableOperation operation) {
        var body = operation.operation().getRequestBody();
        if (body != null
                && (read(operation.method()) && Boolean.TRUE.equals(body.getRequired())
                        || body.getContent() == null
                        || !body.getContent().containsKey("application/json"))) return true;
        if (operation.parameters().size() > 200)
            throw new BusinessSecurityException("scenario operation has too many parameters");
        for (var p : operation.parameters()) {
            if (Boolean.TRUE.equals(p.getRequired())
                    && (!Set.of("path", "query").contains(p.getIn() == null ? "" : p.getIn())
                            || !BusinessScenarioGraph.identifier(p.getName()))) return true;
        }
        return false;
    }

    private static boolean read(String method) {
        return Set.of("GET", "HEAD", "OPTIONS").contains(method);
    }

    private void reviewNeeds(ObjectNode step, List<Need> needs) {
        String id = step.path("id").asText();
        needs.add(new Need(id, "statuses", null, "declare-reviewed-expected-statuses"));
        var matcher =
                Pattern.compile("\\$\\{(input_[a-zA-Z0-9_]+)}")
                        .matcher(step.path("request").toString());
        Set<String> found = new java.util.TreeSet<>();
        while (matcher.find()) found.add(matcher.group(1));
        found.forEach(
                binding ->
                        needs.add(
                                new Need(
                                        id,
                                        "input",
                                        binding,
                                        "supply-project-fixture-or-payload-value")));
    }

    private Draft blocked(BusinessScenarioSeed seed, String reason) {
        return new Draft(
                seed.id(),
                "blocked",
                List.of(),
                List.of(new Need("suite", "blocked", null, reason)),
                null);
    }

    private void write(BusinessScenarioReport report) {
        try {
            Path dir = Path.of(reports.outputDir()).resolve("scenario-drafts");
            Files.createDirectories(dir);
            Path json = dir.resolve("business-scenario-drafts.json");
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            register(json, "application/json");
            StringBuilder markdown =
                    new StringBuilder(
                            "# Business scenario drafts\n\n"
                                + "No security measurements or traffic. Every draft requires policy"
                                + " review.\n\n"
                                + "| Draft | Status | Chain |\n"
                                + "|---|---|---|\n");
            for (var draft : report.drafts()) {
                markdown.append("| ")
                        .append(draft.id())
                        .append(" | ")
                        .append(draft.status())
                        .append(" | ")
                        .append(String.join(" → ", draft.chain()))
                        .append(" |\n");
                Path file = dir.resolve(draft.id() + "-draft.json");
                JsonNode template =
                        draft.suite() == null
                                ? mapper.createObjectNode().put("status", "blocked")
                                : draft.suite();
                mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), template);
                register(file, "application/json");
            }
            Path md = dir.resolve("business-scenario-drafts.md");
            Files.writeString(md, markdown);
            register(md, "text/markdown");
        } catch (Exception e) {
            throw new BusinessSecurityException("scenario draft artifacts could not be written");
        }
    }

    private void register(Path file, String mediaType) {
        sink.registerFile(
                "module-security",
                "business-scenarios",
                file.getFileName().toString(),
                file,
                mediaType,
                Map.of("measured", "false"));
    }
}
