package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.List;
import org.junit.jupiter.api.Test;

class EndpointCatalogBuilderTest {

    private final OpenApiDocument document =
            new OpenApiReader(new ObjectMapper(), null, null).read("classpath:openapi/orders.yaml");
    private final EndpointCatalog catalog =
            new EndpointCatalogBuilder().build(document, "https://api.example.test");

    @Test
    void listsEveryOperationInAStableOrder() {
        assertThat(catalog.endpoints())
                .extracting(EndpointDescriptor::label)
                .containsExactly(
                        "GET /legacy/ping",
                        "GET /orders",
                        "GET /orders/{id}",
                        "DELETE /orders/{id}");

        assertThat(catalog.title()).isEqualTo("Orders API");
        assertThat(catalog.baseUrl()).isEqualTo("https://api.example.test");
    }

    @Test
    void pathLevelParametersAreInheritedAndReferencesResolved() {
        EndpointDescriptor getOrders = endpoint("getOrders");

        assertThat(getOrders.parameters())
                .extracting(ParameterDescriptor::name)
                .containsExactly("X-Tenant", "status");
        assertThat(getOrders.requiredQueryParameters())
                .extracting(ParameterDescriptor::name)
                .containsExactly("status");
    }

    @Test
    void pathParametersAreRequiredEvenWhenTheDocumentOmitsIt() {
        assertThat(endpoint("getOrderById").pathParameters())
                .extracting(ParameterDescriptor::name, ParameterDescriptor::required)
                .containsExactly(org.assertj.core.api.Assertions.tuple("id", true));
    }

    @Test
    void artifactNamesComeFromTheOperationIdAndStayFileSafe() {
        assertThat(catalog.endpoints())
                .extracting(EndpointDescriptor::artifactName)
                .containsExactly("legacyping", "getorders", "getorderbyid", "deleteorder");
    }

    @Test
    void deprecationIsCarriedThrough() {
        assertThat(endpoint("legacyPing").deprecated()).isTrue();
        assertThat(endpoint("getOrders").deprecated()).isFalse();
    }

    @Test
    void theCatalogNeverCarriesTheRawSpecification() throws Exception {
        String json = new ObjectMapper().writeValueAsString(catalog);

        assertThat(json).contains("getOrders", "/orders/{id}");
        assertThat(json).doesNotContain("operationId\":{", "responses", "components");
    }

    private EndpointDescriptor endpoint(String operationId) {
        List<EndpointDescriptor> matches = catalog.endpoints().stream()
                .filter(endpoint -> operationId.equals(endpoint.operationId()))
                .toList();
        assertThat(matches).hasSize(1);
        return matches.getFirst();
    }
}
