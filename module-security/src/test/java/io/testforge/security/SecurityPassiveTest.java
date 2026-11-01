package io.testforge.security;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecurityPassiveTest {
  @Test
  void applicabilitySecretsAndDuplicatesAreHandledWithoutStoringValues() throws Exception {
    var collector = new SecurityCollector(1000);
    var checks =
        new PassiveSecurityChecks(
            new SecurityProperties.Passive(
                true,
                null,
                List.of("$.contact.email"),
                null,
                List.of("https://untrusted.example.test"),
                null),
            collector);
    String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJzeW50aGV0aWMifQ.c3ludGhldGljLXNpZ25hdHVyZQ";
    var headers =
        Map.of(
            "Content-Type",
            List.of("application/json"),
            "Set-Cookie",
            List.of("SESSION=synthetic-cookie; SameSite=None"),
            "Access-Control-Allow-Origin",
            List.of("*"),
            "Access-Control-Allow-Credentials",
            List.of("true"));
    for (int i = 0; i < 2; i++)
      checks.inspect(
          "GET",
          "http://localhost/accounts/123?api_key=synthetic-query",
          true,
          null,
          200,
          headers,
          "{\"jwt\":\""
              + jwt
              + "\",\"card\":\"4111 1111 1111"
              + " 1111\",\"contact\":{\"email\":\"person@example.test\"}}");
    assertThat(collector.findings())
        .extracting(SecurityFinding::check)
        .contains(
            "token-exposure",
            "card-exposure",
            "forbidden-field",
            "cors-credentials-wildcard",
            "cookie-secure",
            "cookie-httponly")
        .doesNotContain("missing-hsts", "missing-csp");
    assertThat(collector.findings()).extracting(SecurityFinding::id).doesNotHaveDuplicates();
    String safe =
        new ObjectMapper().writeValueAsString(List.of(collector.findings(), collector.coverage()));
    assertThat(safe)
        .doesNotContain(
            jwt, "4111", "person@example.test", "synthetic-cookie", "synthetic-query", "/123");
    checks.inspect(
        "GET",
        "https://example.test/page",
        false,
        "https://trusted.example.test",
        200,
        Map.of(
            "Content-Type",
            List.of("text/html"),
            "Access-Control-Allow-Origin",
            List.of("https://trusted.example.test")),
        "ok");
    assertThat(collector.findings())
        .extracting(SecurityFinding::check)
        .contains("missing-hsts", "missing-csp")
        .doesNotContain("cors-untrusted-reflection");
  }

  @Test
  void healthyCookiesHtmlAndErrorHygieneAreContextual() {
    var c = new SecurityCollector(1000);
    var checks =
        new PassiveSecurityChecks(
            new SecurityProperties.Passive(true, null, null, null, null, null), c);
    checks.inspect(
        "GET",
        "https://example.test/ok",
        true,
        null,
        200,
        Map.of(
            "Content-Type",
            List.of("text/html"),
            "X-Content-Type-Options",
            List.of("nosniff"),
            "Strict-Transport-Security",
            List.of("max-age=31536000"),
            "Content-Security-Policy",
            List.of("default-src 'none'; frame-ancestors 'none'"),
            "Cache-Control",
            List.of("no-store"),
            "Set-Cookie",
            List.of("SESSION=synthetic; Secure; HttpOnly; SameSite=Lax")),
        "ok");
    assertThat(c.findings()).isEmpty();
    checks.inspect(
        "GET",
        "https://example.test/error",
        false,
        null,
        500,
        Map.of("X-Content-Type-Options", List.of("nosniff")),
        "at com.example.Service.run(Service.java:42) SQLSTATE[123] 192.168.1.2");
    assertThat(c.findings())
        .extracting(SecurityFinding::check)
        .contains("error-disclosure", "internal-address");
    assertThat(c.findings().toString()).doesNotContain("Service.java", "192.168.1.2");
  }

  @Test
  void baselineIsExplicitAcceptedFindingNeedsReasonAndReportsAreValueFree(@TempDir Path dir)
      throws Exception {
    var p = properties(dir, List.of());
    var c = new SecurityCollector(100);
    c.finding(
        "token-exposure",
        Severity.HIGH,
        "GET /accounts",
        "credential-like pattern observed",
        "API8:2023");
    c.covered("token-exposure", "GET /accounts", null, "measured", null);
    var runner = new SecurityRunner(p, c, ArtifactSink.NO_OP);
    assertThatThrownBy(runner::assertAcceptable).isInstanceOf(AssertionError.class);
    assertThat(Path.of(p.baselineFile())).doesNotExist();
    runner.writeBaseline();
    assertThat(Path.of(p.baselineFile())).exists();
    String before = Files.readString(Path.of(p.baselineFile()));
    assertThat(runner.assertAcceptable().findings())
        .singleElement()
        .extracting(SecurityFinding::status)
        .isEqualTo("accepted");
    runner.run();
    assertThat(Files.readString(Path.of(p.baselineFile()))).isEqualTo(before);
    var accepted =
        new SecurityRunner(
                properties(dir, List.of("token-exposure GET /accounts # SEC-1 synthetic fixture")),
                c,
                ArtifactSink.NO_OP)
            .assertAcceptable();
    assertThat(accepted.findings())
        .singleElement()
        .extracting(SecurityFinding::status)
        .isEqualTo("accepted");
    var incomplete = new SecurityCollector(100);
    incomplete.covered("token-exposure", "GET /accounts", null, "not_measured", "body truncated");
    var incompleteReport = new SecurityRunner(p, incomplete, ArtifactSink.NO_OP).run();
    assertThat(incompleteReport.findings()).noneMatch(f -> f.status().equals("resolved"));
    assertThat(incompleteReport.healthy()).isFalse();
    assertThatThrownBy(() -> new SecurityRunner(p, incomplete, ArtifactSink.NO_OP).writeBaseline())
        .isInstanceOf(IllegalStateException.class);
    var clean = new SecurityCollector(100);
    clean.covered("token-exposure", "GET /accounts", null, "measured", null);
    assertThat(new SecurityRunner(p, clean, ArtifactSink.NO_OP).run().findings())
        .singleElement()
        .extracting(SecurityFinding::status)
        .isEqualTo("resolved");
    assertThat(
            new ObjectMapper()
                .readTree(dir.resolve("security.sarif").toFile())
                .path("version")
                .asText())
        .isEqualTo("2.1.0");
    assertThatThrownBy(() -> properties(dir, List.of("token-exposure GET /accounts")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void disabledUnmeasuredAndLimitedRunsCannotPassTheGate(@TempDir Path dir) {
    assertThatThrownBy(
            () ->
                new SecurityRunner(
                        properties(dir, List.of()), new SecurityCollector(1), ArtifactSink.NO_OP)
                    .assertAcceptable())
        .isInstanceOf(AssertionError.class);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).doesNotHaveBean(PassiveSecurityFilter.class);
              assertThat(context.getBean(SecurityRunner.class).run().enabled()).isFalse();
            });
    var c = new SecurityCollector(1);
    c.covered("one", "run", null, "measured", null);
    c.covered("two", "run", null, "measured", null);
    assertThatThrownBy(
            () ->
                new SecurityRunner(properties(dir, List.of()), c, ArtifactSink.NO_OP)
                    .assertAcceptable())
        .isInstanceOf(AssertionError.class);
  }

  static SecurityProperties properties(Path dir, List<String> accepted) {
    return new SecurityProperties(
        new SecurityProperties.Passive(true, null, null, null, null, null),
        dir.toString(),
        dir.resolve("baseline.json").toString(),
        accepted,
        null,
        null);
  }
}
