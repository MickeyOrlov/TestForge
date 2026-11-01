package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.auth.*;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.http.*;
import io.testforge.security.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecurityAuthExampleTest {
  @Test
  void projectCreatesAnOwnedFixtureThenChecksAnonymousAndOtherRoleAccess() {
    var server =
        new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));
    server.start();
    try {
      server.stubFor(
          post(urlEqualTo("/orders"))
              .atPriority(10)
              .willReturn(
                  created()
                      .withHeader("Content-Type", "application/json")
                      .withBody("{\"id\":\"owned-fixture\"}")));
      // Deliberately broken object authorization: every caller sees the owner's fixture.
      server.stubFor(
          get(urlEqualTo("/orders/owned-fixture"))
              .atPriority(10)
              .willReturn(okJson("{\"id\":\"owned-fixture\"}")));
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  TestForgeCoreAutoConfiguration.class,
                  TestForgeHttpAutoConfiguration.class,
                  TestForgeAuthAutoConfiguration.class,
                  TestForgeSecurityAutoConfiguration.class))
          .withPropertyValues(
              "forge.http.base-url=http://127.0.0.1:" + server.port(),
              "forge.auth.enabled=true",
              "forge.auth.roles.owner.type=static",
              "forge.auth.roles.owner.token=synthetic-owner",
              "forge.auth.roles.other.type=static",
              "forge.auth.roles.other.token=synthetic-other",
              "forge.security.auth.enabled=true",
              "forge.security.auth.allowed-hosts[0]=127.0.0.1",
              "forge.security.output-dir=build/security-auth-example")
          .run(
              c -> {
                var api = c.getBean(ApiClient.class);
                String id =
                    AuthContext.callAs(
                        "owner",
                        () ->
                            api.request()
                                .contentType("application/json")
                                .body("{}")
                                .post("/orders")
                                .then()
                                .statusCode(201)
                                .extract()
                                .path("id"));
                var resource =
                    new PreparedRequest(
                        "GET", "/orders/{orderId}", Map.of("orderId", id), Map.of());
                var checks = c.getBean(SecurityAuthRunner.class);
                checks.unauthenticated(resource);
                checks.objectAccess(resource, "owner", "other");
                var assessment = c.getBean(SecurityRunner.class).run();
                assertThat(assessment.findings())
                    .extracting(SecurityFinding::check)
                    .contains("unauthenticated", "bola");
                assertThatThrownBy(c.getBean(SecurityRunner.class)::assertAcceptable)
                    .isInstanceOf(AssertionError.class);
              });
    } finally {
      server.stop();
    }
  }
}
