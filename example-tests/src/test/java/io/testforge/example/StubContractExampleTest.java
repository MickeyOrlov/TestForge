package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.testforge.api.explorer.StubContractChecker;
import io.testforge.mock.MockScope;
import io.testforge.mock.ScopedMockClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A mock that answers what the real service never would makes a test pass
 * against a service that does not exist. Here the downstream's document is
 * {@code demo-api.yaml}; the environment's default stubs and a scenario's
 * scoped stub are both held to it.
 */
@SpringBootTest(properties = {
        "forge.api-discovery.specs.downstream.location=classpath:/openapi/demo-api.yaml",
        "forge.stub-contract.enabled=true"
})
class StubContractExampleTest {

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.mock.base-url", () -> "http://localhost:" + server.port());
    }

    @Autowired
    StubContractChecker stubContract;

    @Autowired
    ScopedMockClient mocks;

    @BeforeEach
    void defaults() {
        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/api/v1/tasks")).atPriority(10)
                .willReturn(okJson("{\"items\":[{\"id\":\"t-1\",\"title\":\"Demo\",\"status\":\"open\"}]}")));
    }

    @Test
    void theEnvironmentsDefaultStubsHonourTheDocument() {
        stubContract.assertHonoured(WireMock.create().host("localhost").port(server.port()).build());
    }

    @Test
    void aScopedStubThatDroppedARequiredFieldIsCaught() {
        try (MockScope scope = mocks.scope()) {
            StubMapping stub = scope.stub(get(urlPathEqualTo("/api/v1/tasks"))
                    .willReturn(okJson("{\"items\":[{\"id\":\"t-2\",\"title\":\"No status\"}]}")));

            assertThatThrownBy(() -> stubContract.assertHonoured(stub))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("GET /api/v1/tasks → 200: MISSING_REQUIRED_FIELD at $.items[].status");
        }
    }
}
