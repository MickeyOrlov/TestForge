package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApiDiscoveryRunnerTest {

    private static final String REQUEST_SHAPE = "post__api_v1_payments__request__application_json.shape.json";

    @TempDir
    Path temp;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesCatalogReportAndShapeSnapshots() throws IOException {
        Path spec = writeSpec(paymentSpec());

        ApiDiscoveryReport report = runner(spec).run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.specs()).hasSize(1);
        assertThat(report.specs().getFirst().endpoints()).isEqualTo(1);
        assertThat(Path.of(report.specs().getFirst().catalogArtifact())).exists();
        assertThat(report.specs().getFirst().shapes())
                .extracting(ApiShapeReport::name)
                .contains(REQUEST_SHAPE, "post__api_v1_payments__response__201__application_json.shape.json");
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("API Discovery Report")
                .contains("billing");
    }

    @Test
    void baselineCatalogDiffFindsAddedRemovedAndChangedEndpoints() throws IOException {
        Path spec = writeSpec(paymentSpec());
        Files.createDirectories(temp.resolve("baseline/billing"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                temp.resolve("baseline/billing/catalog.json").toFile(),
                new EndpointCatalog("billing", List.of(
                        new ApiEndpoint(
                                "GET /legacy",
                                "GET",
                                "/legacy",
                                "legacy",
                                List.of(),
                                List.of(),
                                Map.of("200", List.of("application/json")),
                                false),
                        new ApiEndpoint(
                                "POST /api/v1/payments",
                                "POST",
                                "/api/v1/payments",
                                "oldCreatePayment",
                                List.of(),
                                List.of("application/json"),
                                Map.of("201", List.of("application/json")),
                                false))));

        ApiDiscoveryReport report = runner(spec).run();

        assertThat(report.healthy()).isFalse();
        CatalogDiff diff = report.specs().getFirst().catalogDiff();
        assertThat(diff.removed()).containsKey("GET /legacy");
        assertThat(diff.changed())
                .extracting(CatalogDiff.EndpointChange::key)
                .containsExactly("POST /api/v1/payments");
    }

    @Test
    void baselineShapeDiffFindsRemovedAndChangedFields() throws IOException {
        Path spec = writeSpec(paymentSpec());
        Files.createDirectories(temp.resolve("baseline/billing/shapes"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                temp.resolve("baseline/billing/shapes/" + REQUEST_SHAPE).toFile(),
                new ApiSchemaShape(
                        REQUEST_SHAPE,
                        "POST /api/v1/payments",
                        "POST",
                        "/api/v1/payments",
                        "request",
                        null,
                        "application/json",
                        Map.of(
                                "$", new SchemaShapeEntry("OBJECT", true, false),
                                "$.amount", new SchemaShapeEntry("INTEGER", true, false),
                                "$.legacy", new SchemaShapeEntry("STRING", false, false))));

        ApiDiscoveryReport report = runner(spec).run();

        assertThat(report.healthy()).isFalse();
        ApiShapeDiff diff = report.specs().getFirst().shapes().stream()
                .filter(shape -> shape.name().equals(REQUEST_SHAPE))
                .findFirst()
                .orElseThrow()
                .shapeDiff();
        assertThat(diff.changed())
                .extracting(change -> change.path() + ": " + change.baseline().type() + " -> " + change.current().type())
                .contains("$.amount: INTEGER -> NUMBER");
        assertThat(diff.removed()).containsKey("$.legacy");
    }

    @Test
    void malformedSpecProducesReadableFailedReport() throws IOException {
        Path spec = temp.resolve("broken.yaml");
        Files.writeString(spec, "openapi: [");

        ApiDiscoveryReport report = runner(spec).run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.specs().getFirst().error()).contains("Failed to parse OpenAPI spec");
    }

    private ApiDiscoveryRunner runner(Path spec) {
        return new ApiDiscoveryRunner(
                new OpenApiSpecParser(),
                new EndpointCatalogBuilder(),
                new OpenApiShapeNormalizer(),
                objectMapper,
                new ApiDiscoveryProperties(
                        true,
                        temp.resolve("current").toString(),
                        temp.resolve("baseline").toString(),
                        true,
                        true,
                        Map.of("billing", new ApiDiscoveryProperties.Spec(spec.toString()))));
    }

    private Path writeSpec(String content) throws IOException {
        Path spec = temp.resolve("openapi.yaml");
        Files.writeString(spec, content);
        return spec;
    }

    private String paymentSpec() {
        return """
                openapi: 3.0.3
                info:
                  title: Billing API
                  version: 1.0.0
                paths:
                  /api/v1/payments:
                    post:
                      operationId: createPayment
                      tags: [payments]
                      requestBody:
                        required: true
                        content:
                          application/json:
                            schema:
                              $ref: '#/components/schemas/CreatePaymentRequest'
                      responses:
                        '201':
                          description: Created
                          content:
                            application/json:
                              schema:
                                $ref: '#/components/schemas/PaymentResponse'
                components:
                  schemas:
                    CreatePaymentRequest:
                      type: object
                      required: [amount, items]
                      properties:
                        amount:
                          type: number
                        note:
                          type: string
                          nullable: true
                        items:
                          type: array
                          items:
                            type: object
                            required: [sku]
                            properties:
                              sku:
                                type: string
                              quantity:
                                type: integer
                    PaymentResponse:
                      type: object
                      required: [id, status]
                      properties:
                        id:
                          type: string
                        status:
                          type: string
                """;
    }
}
