package io.testforge.security;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.*;
import io.testforge.auth.*;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecurityAuthTest {
  final WireMockServer server =
      new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

  @BeforeEach
  void start() {
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop();
  }

  ApplicationContextRunner context(String... extra) {
    byte[] secret = new byte[48];
    new java.security.SecureRandom().nextBytes(secret);
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                TestForgeCoreAutoConfiguration.class,
                TestForgeHttpAutoConfiguration.class,
                TestForgeAuthAutoConfiguration.class,
                TestForgeSecurityAutoConfiguration.class))
        .withPropertyValues(
            "forge.http.base-url=http://127.0.0.1:" + server.port(),
            "forge.security.auth.enabled=true",
            "forge.security.auth.allowed-hosts[0]=127.0.0.1",
            "forge.auth.enabled=true",
            "forge.auth.default-role=admin",
            "forge.auth.roles.admin.type=local-jwt",
            "forge.auth.roles.admin.subject=owner",
            "forge.auth.roles.admin.claims.aud=expected",
            "forge.auth.local-jwt.secret=" + Base64.getEncoder().encodeToString(secret),
            "forge.auth.roles.other.type=static",
            "forge.auth.roles.other.token=synthetic-other",
            "forge.security.output-dir=build/auth-security-test")
        .withPropertyValues(extra);
  }

  static PreparedRequest request(String path) {
    return new PreparedRequest("GET", path, Map.of(), Map.of());
  }

  @Test
  void brokenEndpointsRevealAnonymousJwtBolaAndFunctionAccessWithoutCredentialsInFindings() {
    server.stubFor(get(urlEqualTo("/broken")).atPriority(10).willReturn(okJson("{}")));
    context()
        .withBean(
            ApiRequestCustomizer.class,
            () ->
                (request, service) ->
                    request
                        .header("Authorization", "Bearer sticky-synthetic")
                        .cookie("SESSION", "sticky-synthetic"))
        .run(
            c -> {
              var probe = c.getBean(SecurityAuthRunner.class);
              probe.unauthenticated(request("/broken"));
              probe.jwtRejections(request("/broken"), "admin", "expected");
              probe.objectAccess(request("/broken"), "admin", "other");
              probe.accessMatrix(
                  new AccessMatrixReport(
                      "now",
                      server.baseUrl(),
                      List.of("other"),
                      false,
                      1,
                      0,
                      0,
                      0,
                      List.of(
                          new AccessRow(
                              "test",
                              "getBroken",
                              "GET /broken",
                              null,
                              List.of(
                                  new AccessCell(
                                      "other", 200, "deny", AccessCell.Verdict.VIOLATION, null)))),
                      null,
                      null));
              var report = c.getBean(SecurityRunner.class).run();
              assertThat(report.enabled()).isTrue();
              assertThat(report.healthy()).isFalse();
              assertThat(report.findings())
                  .extracting(SecurityFinding::check)
                  .contains(
                      "unauthenticated",
                      "jwt-expired",
                      "jwt-wrong-signature",
                      "jwt-none",
                      "jwt-tampered-role",
                      "jwt-wrong-audience",
                      "bola",
                      "function-access");
              assertThat(report.toString())
                  .doesNotContain(
                      "sticky-synthetic",
                      "synthetic-other",
                      c.getBean(TokenProvider.class).forRole("admin"));
            });
    var requests = server.findAll(getRequestedFor(urlEqualTo("/broken")));
    assertThat(requests)
        .anySatisfy(
            r -> {
              assertThat(r.getHeader("Authorization")).isNull();
              assertThat(r.getHeader("Cookie")).isNull();
            });
  }

  @Test
  void correctRejectionsHaveCoverageButNoFindingsAndRedirectIsNotFollowed() {
    context()
        .run(
            c -> {
              String valid = c.getBean(TokenProvider.class).forRole("admin");
              server.stubFor(
                  get(urlEqualTo("/secured"))
                      .atPriority(10)
                      .withHeader("Authorization", equalTo("Bearer " + valid))
                      .willReturn(okJson("{}")));
              server.stubFor(
                  get(urlEqualTo("/secured"))
                      .atPriority(20)
                      .willReturn(aResponse().withStatus(403)));
              server.stubFor(
                  get(urlEqualTo("/redirect"))
                      .atPriority(10)
                      .willReturn(
                          aResponse()
                              .withStatus(302)
                              .withHeader("Location", server.baseUrl() + "/must-not-follow")));
              var probe = c.getBean(SecurityAuthRunner.class);
              probe.unauthenticated(request("/secured"));
              probe.jwtRejections(request("/secured"), "admin", "expected");
              probe.objectAccess(request("/secured"), "admin", "other");
              assertThat(c.getBean(SecurityRunner.class).assertAcceptable().findings()).isEmpty();
              probe.unauthenticated(request("/redirect"));
              assertThat(c.getBean(SecurityRunner.class).run().coverage())
                  .anyMatch(x -> x.status().equals("not_measured"));
              assertThatThrownBy(c.getBean(SecurityRunner.class)::assertAcceptable)
                  .isInstanceOf(AssertionError.class);
            });
    server.verify(0, getRequestedFor(urlEqualTo("/must-not-follow")));
  }

  @Test
  void openApiSecurityOverridesAndRequestBudgetAreExplicit() {
    server.stubFor(get(urlEqualTo("/spec-secured")).atPriority(10).willReturn(okJson("{}")));
    context("forge.security.auth.max-requests=1")
        .run(
            c -> {
              var document = new io.swagger.v3.oas.models.OpenAPI();
              document.setSecurity(
                  List.of(
                      new io.swagger.v3.oas.models.security.SecurityRequirement()
                          .addList("bearer")));
              var paths = new io.swagger.v3.oas.models.Paths();
              paths.addPathItem(
                  "/spec-secured",
                  new io.swagger.v3.oas.models.PathItem()
                      .get(new io.swagger.v3.oas.models.Operation().operationId("secured")));
              paths.addPathItem(
                  "/spec-public",
                  new io.swagger.v3.oas.models.PathItem()
                      .get(
                          new io.swagger.v3.oas.models.Operation()
                              .operationId("public")
                              .security(List.of())));
              document.paths(paths);
              var probes = c.getBean(SecurityAuthRunner.class);
              probes.securedOperations(
                  "fixture",
                  document,
                  org.springframework.boot.context.properties.bind.Binder.get(c.getEnvironment())
                      .bindOrCreate("forge.api-explorer", ApiExplorerProperties.class));
              probes.unauthenticated(request("/spec-secured"));
              var coverage = c.getBean(SecurityRunner.class).run().coverage();
              assertThat(coverage).anyMatch(row -> row.status().equals("not_applicable"));
              assertThat(coverage).anyMatch(row -> row.check().equals("traffic-budget"));
            });
    server.verify(1, getRequestedFor(urlEqualTo("/spec-secured")));
    server.verify(0, getRequestedFor(urlEqualTo("/spec-public")));
  }

  @Test
  void targetAndWritePoliciesRefuseBeforeSending() {
    context()
        .run(
            c -> {
              var probe = c.getBean(SecurityAuthRunner.class);
              assertThatThrownBy(
                      () ->
                          probe.unauthenticated(
                              new PreparedRequest("DELETE", "/object", Map.of(), Map.of())))
                  .isInstanceOf(IllegalStateException.class);
              assertThatThrownBy(() -> probe.unauthenticated(request("//evil.test/path")))
                  .isInstanceOf(IllegalArgumentException.class);
            });
    for (String key :
        List.of(
            "forge.security.auth.write-checks=true",
            "forge.security.auth.allow-unsafe-methods=true"))
      context(key)
          .run(
              c ->
                  assertThatThrownBy(
                          () ->
                              c.getBean(SecurityAuthRunner.class)
                                  .unauthenticated(
                                      new PreparedRequest("POST", "/object", Map.of(), Map.of())))
                      .isInstanceOf(IllegalStateException.class));
    context("forge.security.auth.allowed-hosts[0]=unapproved.example.test")
        .run(
            c ->
                assertThatThrownBy(
                        () ->
                            c.getBean(SecurityAuthRunner.class).unauthenticated(request("/object")))
                    .isInstanceOf(IllegalArgumentException.class));
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void failuresAndMissingOwnershipAreNotSecurityPasses() {
    server.stubFor(
        get(urlEqualTo("/unavailable")).atPriority(10).willReturn(aResponse().withStatus(503)));
    context()
        .run(
            c -> {
              c.getBean(SecurityAuthRunner.class)
                  .objectAccess(request("/unavailable"), "admin", "other");
              var report = c.getBean(SecurityRunner.class).run();
              assertThat(report.measured()).isFalse();
              assertThat(report.healthy()).isFalse();
              assertThat(report.findings()).isEmpty();
            });
  }

  @Test
  void declaredNegativeFixtureAndSessionFailuresAreDetectedBehindTwoWriteKeys() {
    server.stubFor(
        post(urlEqualTo("/malformed")).atPriority(10).willReturn(aResponse().withStatus(500)));
    server.stubFor(
        get(urlEqualTo("/prelogin"))
            .atPriority(10)
            .willReturn(ok().withHeader("Set-Cookie", "SESSION=synthetic-fixed; Path=/")));
    server.stubFor(
        post(urlEqualTo("/login"))
            .atPriority(10)
            .willReturn(ok().withHeader("Set-Cookie", "SESSION=synthetic-fixed; Path=/")));
    server.stubFor(post(urlEqualTo("/logout")).atPriority(10).willReturn(ok()));
    server.stubFor(get(urlEqualTo("/private")).atPriority(10).willReturn(okJson("{}")));
    context(
            "forge.security.auth.write-checks=true",
            "forge.security.auth.allow-unsafe-methods=true",
            "forge.auth.roles.web.type=session",
            "forge.auth.roles.web.username=synthetic-user",
            "forge.auth.roles.web.password=synthetic-password",
            "forge.auth.roles.web.session.login-url=" + server.baseUrl() + "/login",
            "forge.auth.roles.web.session.cookie=SESSION")
        .run(
            c -> {
              var probe = c.getBean(SecurityAuthRunner.class);
              probe.malformed(
                  new PreparedRequest(
                      "POST", "/malformed", Map.of(), Map.of(), "{bad json", "application/json"));
              probe.session(
                  "web",
                  request("/prelogin"),
                  new PreparedRequest("POST", "/logout", Map.of(), Map.of()),
                  request("/private"));
              var report = c.getBean(SecurityRunner.class).run();
              assertThat(report.findings())
                  .extracting(SecurityFinding::check)
                  .contains("malformed-error", "session-fixation", "session-logout");
              assertThat(report.toString()).doesNotContain("synthetic-fixed", "synthetic-password");
            });
  }
}
