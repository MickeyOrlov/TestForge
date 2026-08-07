package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.shape.PayloadShapeNormalizer;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DeclaredPathProjectorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DeclaredPathProjector projector = new DeclaredPathProjector();

    @Test
    void projectsObjectsAndArraysInTheShapeDialect() {
        OpenApiDocument document = document("""
                {"openapi":"3.0.3","components":{"schemas":{
                  "Order":{"type":"object","properties":{
                    "id":{"type":"string"},
                    "items":{"type":"array","items":{"type":"object","properties":{"sku":{"type":"string"}}}}}}}}}""");

        Set<String> declared = projector.project(document, node("""
                {"$ref":"#/components/schemas/Order"}"""));

        assertThat(declared).containsExactlyInAnyOrder(
                "$", "$.id", "$.items", "$.items[]", "$.items[].sku");
    }

    @Test
    void declaredPathsLineUpWithWhatTheNormalizerProduces() {
        OpenApiDocument document = document("""
                {"openapi":"3.0.3","components":{"schemas":{
                  "OrderList":{"type":"array","items":{"type":"object","properties":{
                    "id":{"type":"string"},"total":{"type":"number"}}}}}}}""");

        Set<String> declared = projector.project(document, node("""
                {"$ref":"#/components/schemas/OrderList"}"""));
        Set<String> observed = new PayloadShapeNormalizer()
                .normalize("""
                        [{"id":"ord-1","total":10.5}]""")
                .keySet();

        // the whole undeclared-field check rests on these two agreeing
        assertThat(declared).containsAll(observed);
    }

    @Test
    void unionsTheBranchesOfOneOf() {
        Set<String> declared = projector.project(document("{\"openapi\":\"3.0.3\"}"), node("""
                {"oneOf":[
                  {"type":"object","properties":{"card":{"type":"string"}}},
                  {"type":"object","properties":{"iban":{"type":"string"}}}]}"""));

        assertThat(declared).containsExactlyInAnyOrder("$", "$.card", "$.iban");
    }

    @Test
    void mergesTheBranchesOfAllOf() {
        Set<String> declared = projector.project(document("{\"openapi\":\"3.0.3\"}"), node("""
                {"allOf":[
                  {"type":"object","properties":{"id":{"type":"string"}}},
                  {"type":"object","properties":{"createdAt":{"type":"string"}}}]}"""));

        assertThat(declared).containsExactlyInAnyOrder("$", "$.id", "$.createdAt");
    }

    @Test
    @Timeout(5)
    void selfReferencingSchemasTerminate() {
        OpenApiDocument document = document("""
                {"openapi":"3.0.3","components":{"schemas":{
                  "Node":{"type":"object","properties":{
                    "name":{"type":"string"},
                    "child":{"$ref":"#/components/schemas/Node"}}}}}}""");

        Set<String> declared = projector.project(document, node("""
                {"$ref":"#/components/schemas/Node"}"""));

        assertThat(declared).contains("$", "$.name", "$.child");
    }

    @Test
    void fieldsNeedingBracketNotationKeepIt() {
        Set<String> declared = projector.project(document("{\"openapi\":\"3.0.3\"}"), node("""
                {"type":"object","properties":{"content-type":{"type":"string"}}}"""));

        assertThat(declared).containsExactlyInAnyOrder("$", "$['content-type']");
    }

    private OpenApiDocument document(String json) {
        return new OpenApiDocument("3.0.3", "test", "1", "inline", node(json));
    }

    private JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
