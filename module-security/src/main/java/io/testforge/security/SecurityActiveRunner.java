package io.testforge.security;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.auth.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/** Delegates to ZAP only through a scoped read-only gateway, never to document-supplied servers. */
public class SecurityActiveRunner {
  private final SecurityActiveProperties p;
  private final SecurityCollector collector;
  private final ZapEngine engine;
  private final TokenProvider tokens;
  private final AuthProperties auth;
  private final ObjectMapper mapper = io.swagger.v3.core.util.Json.mapper();

  public SecurityActiveRunner(
      SecurityActiveProperties p,
      SecurityCollector c,
      ZapEngine e,
      TokenProvider t,
      AuthProperties a) {
    this.p = p;
    this.collector = c;
    this.engine = e;
    this.tokens = t;
    this.auth = a;
  }

  public void scan(OpenAPI document, String target) {
    if (!p.enabled() || !p.testEnvironment())
      throw new IllegalStateException(
          "active scan requires enabled and explicit test-environment=true");
    URI uri = new SecurityTargetPolicy(p.allowedHosts()).require(target);
    if (uri.getQuery() != null || uri.getFragment() != null)
      throw new IllegalArgumentException("active target must not contain query or fragment");
    if (document.getOpenapi() == null || !document.getOpenapi().startsWith("3.0."))
      throw new IllegalArgumentException(
          "initial active scanner integration accepts parsed OpenAPI 3.0 documents only");
    if (p.role() != null
        && !p.role().isBlank()
        && (tokens == null || auth == null || !tokens.roles().contains(p.role())))
      throw new IllegalStateException("active.role requires a configured module-auth role");
    ObjectNode spec = mapper.valueToTree(document);
    List<SecurityScanGateway.Route> routes = routes(spec);
    if (routes.isEmpty())
      throw new IllegalArgumentException("active scan has no read-only operations");
    scrub(spec);
    Path privateDirectory = null;
    try (var gateway = new SecurityScanGateway(uri, p, routes, this::credential)) {
      privateDirectory = Files.createTempDirectory("testforge-security-zap-");
      try {
        Files.setPosixFilePermissions(
            privateDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
      }
      spec.set("servers", mapper.valueToTree(List.of(Map.of("url", gateway.scannerBase()))));
      write(privateDirectory.resolve("openapi.json"), spec);
      write(privateDirectory.resolve("plan.json"), plan(gateway.scannerBase()));
      ZapEngine.Result result = engine.execute(privateDirectory, p);
      if (result == null
          || !result.complete()
          || result.report() == null
          || !result.report().path("site").isArray()
          || gateway.succeeded.isEmpty()) {
        incomplete(
            result != null && !result.complete()
                ? "ZAP "
                    + result.phase()
                    + " failed (exit "
                    + result.exitCode()
                    + "); raw scanner output omitted"
                : "ZAP returned invalid evidence or reached no successful operation");
        return;
      }
      normalize(result.report(), gateway.scannerBase(), routes);
      collector.observedBlock("active");
      for (var route : routes) {
        String targetKey = route.method() + " " + route.template();
        boolean visited = gateway.succeeded.contains(targetKey);
        collector.covered(
            "zap-scan",
            targetKey,
            p.role(),
            visited ? "measured" : "not_measured",
            visited
                ? "configured ZAP policy completed on this visited route; not a per-payload"
                    + " coverage claim"
                : "operation had no successful control response during scanning");
      }
      for (var route : routes) {
        String targetKey = route.method() + " " + route.template();
        boolean measured = gateway.succeeded.contains(targetKey) && !gateway.incomplete.get();
        collector.covered(
            policyKey(),
            targetKey,
            p.role(),
            measured ? "measured" : "not_measured",
            "pinned scanner, configured policy and role; completed-route evidence, not a"
                + " per-payload coverage claim");
      }
      for (String exclusion : new java.util.TreeSet<>(gateway.excludedKinds))
        collector.covered(
            "zap-scope-exclusion",
            "outside-declared-scope",
            p.role(),
            "not_applicable",
            "blocked scanner request outside the configured routes: " + exclusion);
      if (gateway.incomplete.get())
        incomplete("gateway limited the scan: " + new java.util.TreeSet<>(gateway.refusalKinds));
    } catch (Exception e) {
      incomplete("scanner could not complete safely");
    } finally {
      if (privateDirectory != null && !remove(privateDirectory))
        incomplete("private scanner files could not be removed");
    }
  }

  public void baselinePages(List<String> paths, String target) {
    if (p.mode() != SecurityActiveProperties.Mode.BASELINE)
      throw new IllegalStateException("baselinePages requires active.mode=baseline");
    OpenAPI doc =
        new OpenAPI()
            .openapi("3.0.3")
            .info(new io.swagger.v3.oas.models.info.Info().title("Configured pages").version("1"));
    var pagePaths = new io.swagger.v3.oas.models.Paths();
    for (String path : paths) {
      SecurityTargetPolicy.requireRelative(path);
      pagePaths.addPathItem(
          path,
          new io.swagger.v3.oas.models.PathItem()
              .get(
                  new io.swagger.v3.oas.models.Operation()
                      .responses(
                          new io.swagger.v3.oas.models.responses.ApiResponses()
                              .addApiResponse(
                                  "200",
                                  new io.swagger.v3.oas.models.responses.ApiResponse()
                                      .description("page")))));
    }
    doc.paths(pagePaths);
    scan(doc, target);
  }

  private List<SecurityScanGateway.Route> routes(ObjectNode spec) {
    if (!spec.path("paths").isObject()) return List.of();
    var result = new ArrayList<SecurityScanGateway.Route>();
    var paths = (ObjectNode) spec.path("paths");
    paths
        .fields()
        .forEachRemaining(
            entry -> {
              String path = entry.getKey();
              SecurityTargetPolicy.requireRelative(path);
              if (!entry.getValue().isObject())
                throw new IllegalArgumentException("unresolved path item");
              var item = (ObjectNode) entry.getValue();
              var names = new ArrayList<String>();
              item.fieldNames().forEachRemaining(names::add);
              for (String name : names)
                if (Set.of("get", "head", "options").contains(name))
                  result.add(
                      new SecurityScanGateway.Route(
                          name.toUpperCase(Locale.ROOT), path, routePattern(path)));
                else if (!Set.of("parameters", "summary", "description").contains(name))
                  item.remove(name);
            });
    return List.copyOf(result);
  }

  private static Pattern routePattern(String template) {
    var matcher = Pattern.compile("\\{[^/{}]+}").matcher(template);
    var regex = new StringBuilder();
    int start = 0;
    while (matcher.find()) {
      regex.append(Pattern.quote(template.substring(start, matcher.start()))).append("[^/]+");
      start = matcher.end();
    }
    return Pattern.compile(regex.append(Pattern.quote(template.substring(start))).toString());
  }

  private void scrub(JsonNode node) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      if (object.hasNonNull("$ref") && !object.path("$ref").asText().startsWith("#/"))
        throw new IllegalArgumentException(
            "remote OpenAPI references are not allowed in active scanner input");
      object.remove(
          List.of(
              "servers",
              "externalDocs",
              "callbacks",
              "links",
              "webhooks",
              "requestBody",
              "security",
              "securitySchemes",
              "example",
              "examples",
              "default",
              "enum",
              "extensions"));
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names)
        if (name.startsWith("x-")) object.remove(name);
        else scrub(object.get(name));
    } else if (node.isArray()) node.forEach(this::scrub);
  }

  private Object plan(String gateway) {
    var jobs = new ArrayList<Object>();
    jobs.add(
        Map.of(
            "type",
            "activeScan-config",
            "inputVectors",
            Map.of(
                "urlQueryStringAndDataDrivenNodes",
                Map.of("enabled", true, "addParam", true),
                "postData",
                Map.of("enabled", false),
                "urlPath",
                false,
                "httpHeaders",
                Map.of("enabled", false, "allRequests", false),
                "cookieData",
                Map.of("enabled", false),
                "scripts",
                false)));
    jobs.add(
        Map.of(
            "type",
            "openapi",
            "parameters",
            Map.of(
                "apiFile", "/zap/wrk/openapi.json", "targetUrl", gateway, "context", "security")));
    int minutes = (int) Math.max(1, (p.timeBudget().toSeconds() + 59) / 60);
    if (p.mode() == SecurityActiveProperties.Mode.API)
      jobs.add(
          Map.of(
              "type",
              "activeScan",
              "parameters",
              Map.of(
                  "context",
                  "security",
                  "url",
                  gateway,
                  "maxRuleDurationInMins",
                  minutes,
                  "maxScanDurationInMins",
                  minutes,
                  "threadPerHost",
                  1,
                  "delayInMs",
                  1000 / p.maxRequestsPerSecond(),
                  "scanHeadersAllRequests",
                  false),
              "policyDefinition",
              Map.of(
                  "defaultThreshold",
                  "Off",
                  "defaultStrength",
                  "Low",
                  "rules",
                  p.rules().stream()
                      .sorted()
                      .map(id -> Map.of("id", id, "strength", "Low", "threshold", "Medium"))
                      .toList())));
    jobs.add(Map.of("type", "passiveScan-wait", "parameters", Map.of("maxDuration", minutes)));
    jobs.add(
        Map.of(
            "type",
            "report",
            "parameters",
            Map.of(
                "template",
                "traditional-json",
                "reportDir",
                "/zap/wrk",
                "reportFile",
                "raw-security",
                "displayReport",
                false)));
    return Map.of(
        "env",
        Map.of(
            "contexts",
            List.of(
                Map.of(
                    "name",
                    "security",
                    "urls",
                    List.of(gateway),
                    "includePaths",
                    List.of(Pattern.quote(gateway) + "/.*"))),
            "parameters",
            Map.of("failOnError", true, "failOnWarning", true, "progressToStdout", false)),
        "jobs",
        jobs);
  }

  private SecurityScanGateway.Credential credential() {
    if (p.role() == null || p.role().isBlank()) return SecurityScanGateway.Credential.anonymous();
    if (tokens == null || auth == null)
      throw new IllegalStateException("active role requires module-auth");
    String value = tokens.forRole(p.role());
    var placement =
        tokens
            .placement(p.role())
            .orElseGet(() -> CredentialPlacement.header(auth.header(), auth.scheme()));
    return switch (placement.kind()) {
      case HEADER ->
          new SecurityScanGateway.Credential(
              placement.name(), placement.prefix() + value, null, null);
      case COOKIE -> new SecurityScanGateway.Credential("Cookie", value, null, null);
      case QUERY -> new SecurityScanGateway.Credential(null, null, placement.name(), value);
    };
  }

  private void normalize(JsonNode raw, String gateway, List<SecurityScanGateway.Route> routes) {
    for (JsonNode site : raw.path("site"))
      for (JsonNode alert : site.path("alerts")) {
        String plugin = alert.path("pluginid").asText();
        if (!plugin.matches("[0-9]{1,8}")) continue;
        int risk = alert.path("riskcode").asInt(-1);
        if (risk < 0 || risk > 3) {
          incomplete("scanner risk metadata is invalid");
          continue;
        }
        for (JsonNode instance : alert.path("instances")) {
          String url = instance.path("uri").asText();
          if (!url.startsWith(gateway + "/")) continue;
          URI uri;
          try {
            uri = URI.create(url);
          } catch (RuntimeException e) {
            incomplete("scanner target metadata is invalid");
            continue;
          }
          String basePath = URI.create(gateway).getPath(),
              path = uri.getPath().substring(basePath.length());
          var route =
              routes.stream()
                  .filter(r -> r.path().matcher(path).matches())
                  .findFirst()
                  .orElse(null);
          if (route == null) continue;
          Severity severity =
              switch (risk) {
                case 3 -> Severity.HIGH;
                case 2 -> Severity.MEDIUM;
                case 1 -> Severity.LOW;
                default -> Severity.INFO;
              };
          collector.finding(
              "zap-" + plugin,
              severity,
              route.method() + " " + route.template(),
              "OWASP ZAP reported an alert; raw attack/evidence values omitted",
              "API8:2023");
        }
      }
  }

  private void write(Path path, Object value) throws java.io.IOException {
    mapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    if (Files.size(path) > 8L * 1024 * 1024)
      throw new java.io.IOException("scanner input exceeds supported size");
    try {
      Files.setPosixFilePermissions(
          path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
    } catch (UnsupportedOperationException ignored) {
    }
  }

  private static boolean remove(Path dir) {
    try (var paths = Files.walk(dir)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
      return true;
    } catch (Exception ignored) {
      return false;
    }
  }

  private String policyKey() {
    try {
      String definition =
          "gateway-query-v1|"
              + p.image()
              + "|"
              + p.mode()
              + "|"
              + new java.util.TreeSet<>(p.rules())
              + "|"
              + p.role();
      return "zap-policy-"
          + java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(definition.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("policy identifier unavailable");
    }
  }

  private void incomplete(String reason) {
    collector.observedBlock("active");
    collector.covered("zap-scan", "run", p.role(), "not_measured", reason);
  }
}
