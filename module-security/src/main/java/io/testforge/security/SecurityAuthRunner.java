package io.testforge.security;

import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.explorer.*;
import io.testforge.auth.*;
import io.testforge.http.ApiClient;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicit, bounded probes. Projects own fixtures and expected policies; this class never invents
 * them.
 */
public class SecurityAuthRunner {
  private static final Set<String> READ = Set.of("GET", "HEAD", "OPTIONS");
  private final ApiClient api;
  private final TokenProvider tokens;
  private final AuthProperties auth;
  private final SecurityAuthProperties properties;
  private final SecurityCollector collector;
  private final AtomicInteger requests = new AtomicInteger();

  public SecurityAuthRunner(
      ApiClient api,
      TokenProvider tokens,
      AuthProperties auth,
      SecurityAuthProperties p,
      SecurityCollector c) {
    this.api = api;
    this.tokens = tokens;
    this.auth = auth;
    this.properties = p;
    this.collector = c;
  }

  public void unauthenticated(PreparedRequest request) {
    checkDenied("unauthenticated", request, AuthContext.ANONYMOUS, null, null);
  }

  /**
   * The project supplies an already parsed document from the existing discovery registry/parser.
   */
  public void securedOperations(String specId, OpenAPI document, ApiExplorerProperties explorer) {
    requireEnabled();
    var planner =
        new RequestPlanner(
            new RequestValueResolver(explorer.parameters(), new SchemaValueFactory()),
            explorer.bodies());
    for (var op : new OperationSelector().select(specId, document)) {
      var requirements =
          op.operation().getSecurity() == null
              ? document.getSecurity()
              : op.operation().getSecurity();
      if (requirements == null
          || requirements.isEmpty()
          || requirements.stream().anyMatch(Map::isEmpty)) {
        collector.covered(
            "unauthenticated",
            safe(op.method(), op.pathTemplate()),
            "anonymous",
            "not_applicable",
            "OpenAPI permits anonymous access");
        continue;
      }
      var plan = planner.plan(op);
      if (!plan.sendable()) {
        incomplete(
            "unauthenticated",
            safe(op.method(), op.pathTemplate()),
            "anonymous",
            "request needs project parameter/body data");
        continue;
      }
      unauthenticated(plan.request());
    }
  }

  public void jwtRejections(PreparedRequest request, String role, String requiredAudience) {
    requireEnabled();
    String target = safe(request.method(), request.pathTemplate());
    Response control = send(request, role, null, null);
    if (control == null || control.statusCode() < 200 || control.statusCode() >= 300) {
      incomplete("jwt-control", target, role, "valid credential control did not succeed");
      return;
    }
    String value = tokens.forRole(role);
    JwtNegativeCases.tokens(auth, role, value, requiredAudience)
        .forEach((name, token) -> checkDenied(name, request, AuthContext.ANONYMOUS, token, null));
  }

  /**
   * Fixture is created by the project's configured chain; owner success is a mandatory positive
   * control.
   */
  public void objectAccess(PreparedRequest request, String ownerRole, String otherRole) {
    requireEnabled();
    if (ownerRole.equals(otherRole))
      throw new IllegalArgumentException("BOLA needs distinct owner and other roles");
    String target = safe(request.method(), request.pathTemplate());
    Response control =
        send(
            new PreparedRequest(
                "GET", request.pathTemplate(), request.pathParameters(), request.queryParameters()),
            ownerRole,
            null,
            null);
    if (control == null || control.statusCode() < 200 || control.statusCode() >= 300) {
      incomplete("bola", target, otherRole, "owner cannot read fixture; ownership control failed");
      return;
    }
    checkDenied("bola", request, otherRole, null, null);
  }

  /** Consume the existing access matrix result without re-running any operation. */
  public void accessMatrix(AccessMatrixReport matrix) {
    requireEnabled();
    for (var row : matrix.rows())
      for (var cell : row.cells()) {
        String target =
            safe(
                row.key().substring(0, row.key().indexOf(' ')),
                row.key().substring(row.key().indexOf(' ') + 1));
        String role = SecurityTargets.field(cell.role());
        if (cell.verdict() == AccessCell.Verdict.ERROR
            || cell.verdict() == AccessCell.Verdict.SKIPPED
            || cell.verdict() == AccessCell.Verdict.UNDECLARED)
          incomplete(
              "function-access", target, role, "matrix cell is errored, skipped or undeclared");
        else {
          collector.covered("function-access", target, role, "measured", null);
          if (cell.verdict() == AccessCell.Verdict.VIOLATION)
            collector.finding(
                "function-access",
                Severity.HIGH,
                target + " role " + role,
                "declared access-matrix expectation was violated",
                "API5:2023");
        }
      }
  }

  /**
   * Explicit negative fixture, so an arbitrary production 500 is not mislabeled as malformed-input
   * failure.
   */
  public void malformed(PreparedRequest request) {
    requireEnabled();
    var response = send(request, AuthContext.ANONYMOUS, null, null);
    String target = safe(request.method(), request.pathTemplate());
    if (response == null) {
      incomplete("malformed-error", target, null, "request failed");
      return;
    }
    collector.covered("malformed-error", target, null, "measured", null);
    if (response.statusCode() >= 500)
      collector.finding(
          "malformed-error",
          Severity.MEDIUM,
          target,
          "explicit malformed-request fixture produced a server error",
          "API8:2023");
  }

  /**
   * Logout writes require both write keys. The saved cookie is retried, not a newly logged-in
   * session.
   */
  public void session(
      String role,
      PreparedRequest preLogin,
      PreparedRequest logout,
      PreparedRequest protectedResource) {
    requireEnabled();
    var config = auth.roles().get(role);
    if (config == null || config.type() != TokenType.SESSION)
      throw new IllegalArgumentException("session checks require a session role");
    Response before = send(preLogin, AuthContext.ANONYMOUS, null, null);
    tokens.invalidate(role);
    String cookie = tokens.forRole(role);
    int sep = cookie.indexOf('=');
    String cookieName = cookie.substring(0, sep), saved = cookie.substring(sep + 1);
    String old = before == null ? null : before.getCookie(cookieName);
    String target = safe(protectedResource.method(), protectedResource.pathTemplate());
    if (old == null)
      incomplete("session-fixation", target, role, "pre-login session cookie absent");
    else {
      collector.covered("session-fixation", target, role, "measured", null);
      if (old.equals(saved))
        collector.finding(
            "session-fixation",
            Severity.HIGH,
            target,
            "session identifier did not rotate after login",
            "API2:2023");
    }
    Response control = send(protectedResource, role, null, null);
    if (control == null || control.statusCode() < 200 || control.statusCode() >= 300) {
      incomplete("session-logout", target, role, "authenticated control failed");
      return;
    }
    try {
      Response exited = send(logout, AuthContext.ANONYMOUS, null, cookie);
      if (exited == null || exited.statusCode() < 200 || exited.statusCode() >= 400) {
        incomplete("session-logout", target, role, "logout step failed");
        return;
      }
      checkDenied("session-logout", protectedResource, AuthContext.ANONYMOUS, null, cookie);
    } finally {
      // the cached session was logged out: the role's next request must log in again
      tokens.invalidate(role);
    }
  }

  private void checkDenied(
      String check, PreparedRequest request, String role, String token, String cookie) {
    requireEnabled();
    String target = safe(request.method(), request.pathTemplate());
    Response response = send(request, role, token, cookie);
    if (response == null || response.statusCode() == 429 || response.statusCode() >= 500) {
      incomplete(check, target, role, "probe failed, was rate-limited or returned server error");
      return;
    }
    int status = response.statusCode();
    if (properties.deniedStatuses().contains(status)) {
      collector.covered(check, target, role, "measured", null);
      return;
    }
    if (status >= 200 && status < 300) {
      collector.covered(check, target, role, "measured", null);
      collector.finding(
          check,
          Severity.HIGH,
          target + " role " + SecurityTargets.field(role),
          "credential or access that should be rejected was accepted",
          check.equals("bola") ? "API1:2023" : "API2:2023");
    } else
      incomplete(check, target, role, "status does not demonstrate access or configured denial");
  }

  private Response send(PreparedRequest request, String role, String token, String cookie) {
    requireEnabled();
    new SecurityTargetPolicy(properties.allowedHosts()).require(api.baseUrl());
    SecurityTargetPolicy.requireRelative(request.pathTemplate());
    String method = request.method().toUpperCase(Locale.ROOT);
    if (!READ.contains(method) && !(properties.writeChecks() && properties.allowUnsafeMethods()))
      throw new IllegalStateException(
          "security write probes require write-checks and allow-unsafe-methods");
    if (requests.incrementAndGet() > properties.maxRequests()) {
      incomplete("traffic-budget", "run", null, "request budget exhausted");
      return null;
    }
    try {
      return AuthContext.callAs(
          role,
          () -> {
            var spec =
                (FilterableRequestSpecification)
                    api.request().noFilters().redirects().follow(false);
            // Deliberately isolate probes from customizers, defaults, retries and signing filters.
            spec.removeHeaders();
            spec.removeCookies();
            spec.auth().none();
            for (String name : new ArrayList<>(spec.getQueryParams().keySet()))
              spec.removeQueryParam(name);
            request.pathParameters().forEach(spec::pathParam);
            request.queryParameters().forEach(spec::queryParam);
            for (String name : tokens.roles())
              tokens
                  .placement(name)
                  .filter(p -> p.kind() == CredentialPlacement.Kind.QUERY)
                  .ifPresent(p -> spec.removeQueryParam(p.name()));
            if (token != null)
              spec.header(
                  auth.header(), auth.scheme().isBlank() ? token : auth.scheme() + " " + token);
            else if (cookie != null) {
              int eq = cookie.indexOf('=');
              spec.cookie(cookie.substring(0, eq), cookie.substring(eq + 1));
            } else spec.filter(new AuthFilter(tokens, auth));
            if (request.body() != null)
              spec.contentType(
                      request.contentType() == null ? "application/json" : request.contentType())
                  .body(request.body());
            return spec.request(method, request.pathTemplate());
          });
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void requireEnabled() {
    if (!properties.enabled())
      throw new IllegalStateException("forge.security.auth.enabled must be true");
  }

  private static String safe(String method, String path) {
    return SecurityTargets.endpoint(
        method, "http://structural.test" + path.replaceAll("\\{[^}]+}", "value"));
  }

  private void incomplete(String check, String target, String role, String reason) {
    collector.covered(
        check, target, role == null ? null : SecurityTargets.field(role), "not_measured", reason);
  }
}
