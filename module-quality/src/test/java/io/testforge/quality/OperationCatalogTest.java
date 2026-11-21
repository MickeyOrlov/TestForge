package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** One name per operation, whatever path form a producer wrote. */
class OperationCatalogTest {

    private final OperationCatalog catalog = OperationCatalog.of(List.of("petclinic GET /owners",
            "petclinic GET /owners/{ownerId}", "petclinic GET /v2/owners", "petclinic DELETE /owners/{ownerId}",
            "petclinic GET /owners/{ownerId}/pets/{petId}", "petclinic GET /pets/{petId}"));

    @Test
    void rawPathsOfEveryProducerMeetAtTheDocumentedTemplate() {
        assertThat(catalog.key("GET /petclinic/api/owners/{n}")).as("module-http, base path and {n}")
                .isEqualTo("petclinic GET /owners/{ownerId}");
        assertThat(catalog.key("DELETE:/petclinic/api/owners/{ownerId}")).as("a WFC operationId")
                .isEqualTo("petclinic DELETE /owners/{ownerId}");
        assertThat(catalog.key("GET /owners/7?page=2")).as("no base path, a query").isEqualTo("petclinic GET /owners/{ownerId}");
        assertThat(catalog.key("GET /petclinic/api/v2/owners")).as("the longer template wins")
                .isEqualTo("petclinic GET /v2/owners");
        assertThat(catalog.key("GET /petclinic/api/owners/3/pets/9"))
                .isEqualTo("petclinic GET /owners/{ownerId}/pets/{petId}");
    }

    @Test
    void aPathTwoDocumentsMatchEquallyWellKeepsItsOwnName() {
        OperationCatalog two = OperationCatalog.of(List.of("billing GET /health", "orders GET /health",
                "orders GET /tasks/{id}"));

        assertThat(two.key("GET /billing/health")).as("not billing's by a guess").isEqualTo("GET /billing/health");
        assertThat(two.key("GET /orders/health")).isEqualTo("GET /orders/health");
        assertThat(two.key("GET /api/tasks/7")).as("one document matches: named").isEqualTo("orders GET /tasks/{id}");
    }

    @Test
    void whatTheDocumentDoesNotKnowKeepsItsOwnName() {
        assertThat(catalog.key("OPTIONS:/petclinic/api/owners")).isEqualTo("OPTIONS:/petclinic/api/owners");
        assertThat(catalog.key("POST /owners")).as("another method").isEqualTo("POST /owners");
        assertThat(catalog.key("operation without a path")).isEqualTo("operation without a path");
        assertThat(OperationCatalog.empty().key("GET /x")).isEqualTo("GET /x");
    }
}
