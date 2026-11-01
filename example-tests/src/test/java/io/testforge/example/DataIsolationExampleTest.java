package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.data.cleanup.CleanupExtension;
import io.testforge.data.factory.Factory;
import io.testforge.data.isolation.DataIsolation;
import io.testforge.data.isolation.DataIsolationExtension;
import io.testforge.http.ApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * On a shared environment a list endpoint returns everyone's data. Tagging
 * what a test creates lets it see only its own, and the factory registers the
 * delete so nothing is left behind.
 */
@SpringBootTest
@ExtendWith({DataIsolationExtension.class, CleanupExtension.class})
class DataIsolationExampleTest {

    static final Factory<Map<String, Object>> CUSTOMERS = Factory.map("customer")
            .field("email", f -> f.tagged() + "@example.test")
            .field("tier", "standard");

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));

    static {
        server.start();
        server.stubFor(post(urlPathEqualTo("/api/v1/customers")).willReturn(aResponse().withStatus(201)
                .withHeader("Content-Type", "application/json").withBody("{\"id\":\"c-1\"}")));
        server.stubFor(delete(urlPathMatching("/api/v1/customers/.+")).willReturn(aResponse().withStatus(204)));
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @AfterAll
    static void theCustomerWasDeletedAfterTheTest() {
        assertThat(server.findAll(deleteRequestedFor(urlPathEqualTo("/api/v1/customers/c-1")))).hasSize(1);
        server.stop();
    }

    @Autowired
    ApiClient api;

    @Autowired
    ObjectMapper json;

    @Test
    void aTestSeesOnlyTheDataItCreated() {
        String id = CUSTOMERS.create(
                customer -> api.request().contentType("application/json").body(write(customer))
                        .post("/api/v1/customers").path("id"),
                created -> api.request().delete("/api/v1/customers/{id}", created).then().statusCode(204));
        assertThat(id).isEqualTo("c-1");

        // what the shared environment would answer: this test's customer among everyone else's
        String mine = DataIsolation.tag("customer-mine") + "@example.test";
        server.stubFor(get(urlPathEqualTo("/api/v1/customers")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody(write(List.of(
                        Map.of("email", mine),
                        Map.of("email", DataIsolation.runTag() + "-t999999-customer-1@example.test"),
                        Map.of("email", "tf-0ldrun-t1-customer-1@example.test"),
                        Map.of("email", "real.person@example.com"))))));

        List<String> emails = api.request().get("/api/v1/customers").jsonPath().getList("email");

        assertThat(CUSTOMERS.build().get("email").toString()).startsWith(DataIsolation.scopeTag() + "-customer-");
        assertThat(emails.stream().filter(DataIsolation::owns)).containsExactly(mine);
        assertThat(emails.stream().filter(DataIsolation::isTestData))
                .as("what a sweeper job may remove").hasSize(3);
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
