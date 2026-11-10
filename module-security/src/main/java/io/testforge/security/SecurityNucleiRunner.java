package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Runs the reviewed Nuclei policy on explicit anonymous GET pages. */
public final class SecurityNucleiRunner {
    private static final String CHECK = "nuclei-headers-v1";
    private static final Pattern PAGE_PATH =
            Pattern.compile("/(?:[A-Za-z0-9._~-]+/)*[A-Za-z0-9._~-]*");
    private static final Set<String> MATCHERS =
            Set.of(
                    "strict-transport-security",
                    "content-security-policy",
                    "permissions-policy",
                    "x-frame-options",
                    "x-content-type-options",
                    "x-permitted-cross-domain-policies",
                    "referrer-policy",
                    "cross-origin-embedder-policy",
                    "cross-origin-opener-policy",
                    "cross-origin-resource-policy",
                    "missing-content-type",
                    "content-type-charset-specification");

    private final SecurityNucleiProperties properties;
    private final SecurityCollector collector;
    private final NucleiEngine engine;

    public SecurityNucleiRunner(
            SecurityNucleiProperties properties, SecurityCollector collector, NucleiEngine engine) {
        this.properties = properties;
        this.collector = collector;
        this.engine = engine;
    }

    public void scanPages(List<String> paths, String target) {
        if (!properties.enabled() || !properties.testEnvironment())
            throw new IllegalStateException(
                    "Nuclei requires enabled and explicit test-environment=true");
        URI uri = new SecurityTargetPolicy(properties.allowedHosts()).require(target);
        if (uri.getRawQuery() != null || !Set.of("", "/").contains(uri.getRawPath()))
            throw new IllegalArgumentException("Nuclei target must be an HTTP(S) origin");
        if (paths == null
                || paths.isEmpty()
                || paths.size() > 50
                || paths.size() > properties.maxRequests())
            throw new IllegalArgumentException("Select 1..50 paths within the request budget");
        for (String path : paths) {
            SecurityTargetPolicy.requireRelative(path);
            if (path.length() > 250
                    || !PAGE_PATH.matcher(path).matches()
                    || !URI.create(path).normalize().getPath().equals(path))
                throw new IllegalArgumentException("Nuclei requires literal configured page paths");
        }
        var routes =
                paths.stream()
                        .distinct()
                        .map(
                                path ->
                                        new SecurityScanGateway.Route(
                                                "GET", path, Pattern.compile(Pattern.quote(path))))
                        .toList();
        collector.observedBlock("nuclei");
        Scan scan;
        try {
            scan = execute(uri, routes);
        } catch (Exception e) {
            scan = new Scan(List.of(), "Nuclei execution or private file cleanup failed");
        }
        for (var route : routes) {
            collector.covered(
                    CHECK,
                    "GET " + route.template(),
                    null,
                    scan.failure() == null ? "measured" : "not_measured",
                    scan.failure() == null
                            ? "Reviewed Nuclei header template completed"
                            : scan.failure());
        }
        for (var match : scan.matches())
            collector.finding(
                    CHECK,
                    Severity.INFO,
                    "GET " + match.path() + " " + match.matcher(),
                    "Nuclei header template matched " + match.matcher(),
                    "API8:2023");
    }

    private Scan execute(URI uri, List<SecurityScanGateway.Route> routes) throws Exception {
        Path dir = Files.createTempDirectory("testforge-nuclei-");
        try (var gateway = new SecurityScanGateway(uri, properties.limits(), routes)) {
            var targets = new LinkedHashMap<String, String>();
            routes.forEach(
                    route ->
                            targets.put(
                                    gateway.scannerBase() + route.template(), route.template()));
            var result = engine.execute(dir, List.copyOf(targets.keySet()), properties);
            if (result == null || result.phase() == null)
                return new Scan(List.of(), "Nuclei returned no execution status");
            if (result.phase() != NucleiEngine.Phase.COMPLETE)
                return new Scan(List.of(), "Nuclei " + result.phase() + " failed");
            if (gateway.incomplete.get()
                    || !gateway.excludedKinds.isEmpty()
                    || gateway.succeeded.size() != targets.size())
                return new Scan(
                        List.of(), "Nuclei did not complete every scoped GET with HTTP 2xx");
            return new Scan(normalize(result.matches(), targets), null);
        } finally {
            if (!SecurityActiveRunner.remove(dir))
                throw new IllegalStateException("Private Nuclei files could not be removed");
        }
    }

    private static List<Match> normalize(JsonNode rows, Map<String, String> targets) {
        if (rows == null || !rows.isArray() || rows.size() > targets.size() * MATCHERS.size())
            throw new IllegalStateException("Invalid Nuclei findings");
        var matches = new ArrayList<Match>();
        for (var row : rows) {
            String path = targets.get(row.path("matched-at").asText());
            String matcher = row.path("matcher-name").asText();
            if (path == null
                    || !MATCHERS.contains(matcher)
                    || !DockerNucleiEngine.TEMPLATE.equals(row.path("template-id").asText())
                    || !"http".equals(row.path("type").asText())
                    || !"info".equals(row.path("info").path("severity").asText()))
                throw new IllegalStateException("Unexpected Nuclei finding");
            matches.add(new Match(path, matcher));
        }
        return List.copyOf(matches);
    }

    private record Match(String path, String matcher) {}

    private record Scan(List<Match> matches, String failure) {}
}
