package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiChangeClassifierTest {

    private static ApiEndpoint endpoint(String key, List<String> requestTypes, Map<String, List<String>> responses,
                                        boolean deprecated) {
        String[] parts = key.split(" ");
        return new ApiEndpoint(key, parts[0], parts[1], "op", List.of(), requestTypes, responses, deprecated);
    }

    @Test
    void catalogChangesAreJudgedForClientsWorstFirst() {
        EndpointCatalog baseline = new EndpointCatalog("billing", List.of(
                endpoint("GET /legacy", List.of(), Map.of("200", List.of("application/json")), false),
                endpoint("POST /payments", List.of("application/json", "application/xml"),
                        Map.of("201", List.of("application/json", "application/xml")), false)));
        EndpointCatalog current = new EndpointCatalog("billing", List.of(
                endpoint("POST /payments", List.of("application/json"),
                        Map.of("201", List.of("application/json"), "409", List.of("application/json")), true),
                endpoint("GET /payments/{id}", List.of(), Map.of("200", List.of("application/json")), false)));

        assertThat(ApiChangeClassifier.catalog(CatalogDiff.between(baseline, current)))
                .extracting(ApiChange::toString).containsExactly(
                        "BREAKING GET /legacy: operation removed",
                        "BREAKING POST /payments: request content types no longer accepted [application/xml]",
                        "BREAKING POST /payments: response 201 no longer sent as [application/xml]",
                        "RISKY POST /payments: new responses [409]: a client may not handle them",
                        "RISKY POST /payments: now deprecated",
                        "SAFE GET /payments/{id}: new operation");
    }

    private static ApiShapeReport report(String direction, Map<String, SchemaShapeEntry> before,
                                         Map<String, SchemaShapeEntry> after) {
        ApiSchemaShape was = new ApiSchemaShape("s", "POST /payments", "POST", "/payments", direction,
                "request".equals(direction) ? null : "201", "application/json", before);
        ApiSchemaShape now = new ApiSchemaShape("s", "POST /payments", "POST", "/payments", direction,
                "request".equals(direction) ? null : "201", "application/json", after);
        return new ApiShapeReport("s", false, "POST /payments", direction, now.statusCode(), now.contentType(),
                ApiShapeDiff.between(was, now), "s");
    }

    @Test
    void aResponseBreaksWhenAFieldMayGoMissingOrNull() {
        ApiShapeReport response = report("response",
                Map.of("$.id", new SchemaShapeEntry("STRING", true, false),
                        "$.status", new SchemaShapeEntry("STRING", true, false),
                        "$.note", new SchemaShapeEntry("STRING", false, false)),
                Map.of("$.id", new SchemaShapeEntry("STRING", false, true),
                        "$.status", new SchemaShapeEntry("INTEGER", true, false),
                        "$.channel", new SchemaShapeEntry("STRING", true, false)));

        assertThat(ApiChangeClassifier.shape(response)).extracting(ApiChange::toString).containsExactly(
                "BREAKING POST /payments response 201 $.id: no longer required",
                "BREAKING POST /payments response 201 $.id: may now be null",
                "BREAKING POST /payments response 201 $.status: type STRING → INTEGER",
                "RISKY POST /payments response 201 $.note: optional field removed",
                "SAFE POST /payments response 201 $.channel: new field");
    }

    @Test
    void aRequestBreaksWhenClientsMustSendSomethingNew() {
        ApiShapeReport request = report("request",
                Map.of("$.amount", new SchemaShapeEntry("NUMBER", true, false),
                        "$.memo", new SchemaShapeEntry("STRING", false, true)),
                Map.of("$.amount", new SchemaShapeEntry("NUMBER", false, false),
                        "$.memo", new SchemaShapeEntry("STRING", false, false),
                        "$.currency", new SchemaShapeEntry("STRING", true, false),
                        "$.tag", new SchemaShapeEntry("STRING", false, false)));

        assertThat(ApiChangeClassifier.shape(request)).extracting(ApiChange::toString).containsExactly(
                "BREAKING POST /payments request $.currency: new required field: clients do not send it",
                "BREAKING POST /payments request $.memo: no longer nullable",
                "SAFE POST /payments request $.amount: no longer required",
                "SAFE POST /payments request $.tag: new field");
    }

    @Test
    void aComposedSchemaChangeIsNotGuessed() {
        ApiShapeReport response = report("response",
                Map.of("$.body", new SchemaShapeEntry("ONE_OF", true, false)),
                Map.of("$.body", new SchemaShapeEntry("OBJECT", true, false)));

        assertThat(ApiChangeClassifier.shape(response)).extracting(ApiChange::impact)
                .containsExactly(ApiChange.Impact.UNKNOWN);
    }
}
