package io.testforge.example.gherkin;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The one Spring context every scenario runs in — a project has exactly one
 * {@code @CucumberContextConfiguration} class in its glue. The WireMock server
 * stands in for the order service.
 */
@CucumberContextConfiguration
@SpringBootTest(properties = "forge.http.scope.header=X-Test-Scope")
public class GherkinExampleContext {

    static final WireMockServer ORDER_SERVICE =
            new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        ORDER_SERVICE.start();
    }

    @DynamicPropertySource
    static void orderService(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + ORDER_SERVICE.port());
    }
}
