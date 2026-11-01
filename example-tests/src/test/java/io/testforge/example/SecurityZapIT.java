package io.testforge.example;

import static org.assertj.core.api.Assertions.*;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.responses.*;
import io.testforge.security.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Tag("containers")
class SecurityZapIT {
  @Test
  void aRealPinnedZapContainerScansOnlyTheOwnedReadOnlySample() throws Exception {
    var count = new AtomicInteger();
    var writes = new AtomicInteger();
    var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/sample",
        e -> {
          count.incrementAndGet();
          if (!Set.of("GET", "HEAD", "OPTIONS").contains(e.getRequestMethod()))
            writes.incrementAndGet();
          byte[] body =
              "{\"message\":\"sample\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.getResponseHeaders().add("Content-Type", "application/json");
          e.sendResponseHeaders(200, e.getRequestMethod().equals("HEAD") ? -1 : body.length);
          if (!e.getRequestMethod().equals("HEAD")) e.getResponseBody().write(body);
          e.close();
        });
    server.start();
    try {
      var document =
          new OpenAPI()
              .openapi("3.0.3")
              .info(new Info().title("Owned local security sample").version("1"))
              .paths(
                  new Paths()
                      .addPathItem(
                          "/sample",
                          new PathItem()
                              .get(
                                  new Operation()
                                      .operationId("sample")
                                      .addParametersItem(
                                          new io.swagger.v3.oas.models.parameters.QueryParameter()
                                              .name("q")
                                              .schema(
                                                  new io.swagger.v3.oas.models.media
                                                      .StringSchema()))
                                      .responses(
                                          new ApiResponses()
                                              .addApiResponse(
                                                  "200",
                                                  new ApiResponse().description("sample"))))));
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
          .withPropertyValues(
              "forge.security.active.enabled=true",
              "forge.security.active.test-environment=true",
              "forge.security.active.allowed-hosts[0]=127.0.0.1",
              "forge.security.active.time-budget=3m",
              "forge.security.active.max-requests=300",
              "forge.security.active.max-requests-per-second=10",
              "forge.security.output-dir=build/security-zap-it")
          .run(
              c -> {
                c.getBean(SecurityActiveRunner.class)
                    .scan(document, "http://127.0.0.1:" + server.getAddress().getPort());
                var report = c.getBean(SecurityRunner.class).run();
                assertThat(report.coverage()).noneMatch(row -> row.status().equals("not_measured"));
                assertThat(report.measured()).isTrue();
                assertThat(count.get()).isGreaterThan(1);
                assertThat(writes).hasValue(0);
              });
    } finally {
      server.stop(0);
    }
  }
}
