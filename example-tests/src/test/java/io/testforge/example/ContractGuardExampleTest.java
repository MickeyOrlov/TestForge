package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.http.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Contract drift caught by an ordinary functional test. Nothing in the test
 * mentions the contract: the guard checks every ApiClient response against
 * {@code demo-api.yaml} on the side. In {@code fail} mode the call that got a
 * drifted response fails; in the default {@code record} mode it would only be
 * reported in the run index.
 */
@SpringBootTest(properties = {
        "forge.api-discovery.specs.demo.location=classpath:/openapi/demo-api.yaml",
        "forge.contract-guard.enabled=true",
        "forge.contract-guard.mode=fail"
})
class ContractGuardExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    ApiClient api;

    @Autowired
    io.testforge.api.explorer.ContractGuard guard;

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void aConformingResponsePassesUnnoticed() {
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\",\"status\":\"open\"}]}")));

        assertThat(api.request().get("/api/v1/tasks").jsonPath().getList("items")).hasSize(1);
    }

    @Test
    void aResponseThatDriftedFromTheDocumentFailsTheTest() {
        // the service dropped "status", which the document says is required
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\"}]}")));

        assertThatThrownBy(() -> api.request().get("/api/v1/tasks"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("does not match the OpenAPI document")
                .hasMessageContaining("MISSING_REQUIRED_FIELD")
                .hasMessageContaining("status");
    }

    @Test
    void theSuiteLearnsWhichDocumentedOperationsNoTestCalled() {
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks"))
                .willReturn(okJson("{\"items\":[]}")));

        api.request().get("/api/v1/tasks");

        // api-coverage.md in the run index lists the same: createTask is documented, nothing calls it
        assertThat(guard.coverage().uncalled()).contains("demo POST /api/v1/tasks");
        assertThat(guard.coverage().called()).containsKey("demo GET /api/v1/tasks");
    }
}
