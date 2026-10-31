package io.testforge.example;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.testforge.data.Generators;
import io.testforge.data.RunUniqueValues;
import io.testforge.data.factory.Factory;
import io.testforge.http.ApiClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tests declare what a valid customer looks like once and override only the
 * field the scenario is about — no hand-built JSON, no shared fixtures.
 */
@SpringBootTest
class FactoryExampleTest {

    /** What the adapted project would keep next to its tests. */
    record Customer(String name, String email, String phone, String tier) {
    }

    static final Factory<Customer> CUSTOMERS = Factory.forRecord(Customer.class)
            .field("name", f -> f.label())
            .field("email", f -> f.get("name") + "@example.test")
            .field("phone", f -> f.unique("phone", Generators.phone("+1555", 7)))
            .field("tier", "standard")
            .trait("vip", customer -> customer.with("tier", "vip"));

    static final Factory<Map<String, Object>> ORDER_PAYLOADS = Factory.map("order")
            .field("reference", f -> f.label())
            .field("item", "book")
            .field("quantity", 1);

    static WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        server.start();
        server.stubFor(post(urlPathEqualTo("/api/v1/orders")).willReturn(aResponse().withStatus(201)));
    }

    @DynamicPropertySource
    static void forgeProperties(DynamicPropertyRegistry registry) {
        registry.add("forge.http.base-url", () -> "http://localhost:" + server.port());
    }

    @AfterAll
    static void stopServer() {
        server.stop();
    }

    @Autowired
    ApiClient api;

    @Autowired
    RunUniqueValues uniqueValues;

    @Autowired
    ObjectMapper json;

    @Test
    void aScenarioOverridesOnlyWhatItIsAbout() {
        Customer anna = CUSTOMERS.as("vip").with("name", "anna").build();

        assertThat(anna.email()).as("derived from the overridden name").isEqualTo("anna@example.test");
        assertThat(anna.tier()).isEqualTo("vip");
        assertThat(anna.phone()).matches("\\+1555\\d{7}");
    }

    @Test
    void sharingTheRunRegistryKeepsValuesUniqueAcrossFactories() {
        Factory<Customer> customers = CUSTOMERS.uniqueValues(uniqueValues);
        List<Customer> batch = customers.build(5);

        assertThat(batch).extracting(Customer::phone).doesNotHaveDuplicates();
        assertThat(uniqueValues.snapshot().get("phone")).containsAll(batch.stream().map(Customer::phone).toList());
    }

    @Test
    void aPayloadWithoutAFieldTestsTheValidation() throws Exception {
        Map<String, Object> missingQuantity = ORDER_PAYLOADS.without("quantity").with("item", "pen").build();

        api.request().contentType("application/json").body(json.writeValueAsString(missingQuantity))
                .post("/api/v1/orders").then().statusCode(201);

        server.verify(postRequestedFor(urlPathEqualTo("/api/v1/orders")).withRequestBody(equalToJson(
                "{\"reference\":\"" + missingQuantity.get("reference") + "\",\"item\":\"pen\"}")));
    }
}
