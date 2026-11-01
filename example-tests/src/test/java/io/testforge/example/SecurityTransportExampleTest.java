package io.testforge.example;

import static org.assertj.core.api.Assertions.*;

import io.testforge.security.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SecurityTransportExampleTest {
  @Test
  void transportChecksAnExplicitLocalTargetWithoutFollowingItsRedirect() throws Exception {
    var server =
        com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        e -> {
          e.getResponseHeaders().add("Location", "https://outside.example.test/");
          e.sendResponseHeaders(302, -1);
          e.close();
        });
    server.start();
    try {
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(TestForgeSecurityAutoConfiguration.class))
          .withPropertyValues(
              "forge.security.transport.enabled=true",
              "forge.security.transport.allowed-hosts[0]=127.0.0.1",
              "forge.security.output-dir=build/security-transport-example")
          .run(
              c -> {
                c.getBean(SecurityTransportRunner.class)
                    .check("http://127.0.0.1:" + server.getAddress().getPort());
                assertThat(c.getBean(SecurityRunner.class).run().findings())
                    .extracting(SecurityFinding::check)
                    .contains("https-redirect");
              });
    } finally {
      server.stop(0);
    }
  }
}
