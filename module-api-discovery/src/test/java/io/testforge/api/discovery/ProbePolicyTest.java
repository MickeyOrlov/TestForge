package io.testforge.api.discovery;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.discovery.ApiDiscoveryProperties.ParameterProperties;
import io.testforge.api.discovery.ApiDiscoveryProperties.ProbeProperties;
import io.testforge.api.discovery.ApiDiscoveryProperties.UnsafeProperties;
import io.testforge.api.discovery.EndpointDescriptor.ParameterDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * One case per {@link SkipReason}. This is the test that has to stay honest:
 * everything else in the module writes files, but this decides what leaves the
 * JVM and hits somebody's staging environment.
 */
class ProbePolicyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void nothingIsCalledUntilProbingIsSwitchedOn() {
        ProbeDecision decision = policy(defaults(), noParameters()).decide(get("/orders"), 0);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.skipReason()).isEqualTo(SkipReason.DISABLED_PROBE);
    }

    @Test
    void safeMethodsPassOnceProbingIsOn() {
        assertThat(policy(enabled(), noParameters()).decide(get("/orders"), 0).allowed()).isTrue();
    }

    @Test
    void deniedPathsAreSkipped() {
        ProbeProperties properties = new ProbeProperties(true, null, null, List.of("/**/logout"),
                null, null, null, null, null, null, null);

        assertThat(policy(properties, noParameters()).decide(get("/session/logout"), 0).skipReason())
                .isEqualTo(SkipReason.PATH_DENIED);
        assertThat(policy(properties, noParameters()).decide(get("/orders"), 0).allowed()).isTrue();
    }

    @Test
    void pathsOutsideTheIncludeListAreSkipped() {
        ProbeProperties properties = new ProbeProperties(true, null, List.of("/orders/**"), null,
                null, null, null, null, null, null, null);

        assertThat(policy(properties, noParameters()).decide(get("/health"), 0).skipReason())
                .isEqualTo(SkipReason.PATH_NOT_INCLUDED);
    }

    @Test
    void operationsCanOptOutThroughTheVendorExtension() {
        EndpointDescriptor endpoint = get("/reports", operation("""
                {"x-testforge-probe": false}"""));

        assertThat(policy(enabled(), noParameters()).decide(endpoint, 0).skipReason())
                .isEqualTo(SkipReason.VENDOR_OPT_OUT);
    }

    @Test
    void deprecatedOperationsAreSkippedByDefault() {
        EndpointDescriptor endpoint = new EndpointDescriptor("legacyping", "GET", "/legacy/ping",
                "legacyPing", null, List.of(), true, List.of(), operation("{}"));

        assertThat(policy(enabled(), noParameters()).decide(endpoint, 0).skipReason())
                .isEqualTo(SkipReason.DEPRECATED);
    }

    @Test
    void unsafeMethodsAreRefusedWhileAnyGateIsShut() {
        EndpointDescriptor delete = endpoint("DELETE", "/orders/{id}", pathParameter("id"));
        ParameterResolution parameters = configured(Map.of("id", "ord-1"));

        assertThat(policy(enabled(), parameters).decide(delete, 0).skipReason())
                .isEqualTo(SkipReason.UNSAFE_METHOD);

        // gate 1 open, gate 2 shut: DELETE is not in unsafe.methods
        assertThat(policy(unsafe(Set.of("POST"), List.of("/orders/{id}"), false), parameters)
                .decide(delete, 0).skipReason())
                .isEqualTo(SkipReason.UNSAFE_METHOD);

        // gates 1-2 open, gate 3 shut: the path was never opted in
        assertThat(policy(unsafe(Set.of("DELETE"), List.of("/nothing"), false), parameters)
                .decide(delete, 0).skipReason())
                .isEqualTo(SkipReason.UNSAFE_METHOD);

        // gates 1-3 open, gate 4 shut
        assertThat(policy(unsafe(Set.of("DELETE"), List.of("/orders/{id}"), false), parameters)
                .decide(delete, 0).skipReason())
                .isEqualTo(SkipReason.DELETE_NOT_ALLOWED);
    }

    @Test
    void unsafeMethodsPassOnlyWithAllFourGatesOpen() {
        ProbeDecision decision = policy(
                unsafe(Set.of("DELETE"), List.of("/orders/{id}"), true),
                configured(Map.of("id", "ord-1")))
                .decide(endpoint("DELETE", "/orders/{id}", pathParameter("id")), 0);

        assertThat(decision.allowed()).isTrue();
    }

    @Test
    void unsafeMethodsRefuseValuesNobodyConfigured() {
        EndpointDescriptor delete = endpoint("DELETE", "/orders/{id}",
                new ParameterDescriptor("id", "path", true, operation("""
                        {"name":"id","in":"path","example":"ord-from-the-spec"}""")));

        ProbeDecision decision = policy(
                unsafe(Set.of("DELETE"), List.of("/orders/{id}"), true),
                new ParameterResolution(List.of(new SpecExampleParameterResolver())))
                .decide(delete, 0);

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.skipReason()).isEqualTo(SkipReason.UNSAFE_PARAMETER_SOURCE);
        assertThat(decision.detail()).contains("SPEC_EXAMPLE");
    }

    @Test
    void unresolvedPathParametersSkipTheEndpoint() {
        ProbeDecision decision = policy(enabled(), noParameters())
                .decide(endpoint("GET", "/orders/{id}", pathParameter("id")), 0);

        assertThat(decision.skipReason()).isEqualTo(SkipReason.MISSING_PATH_PARAMETER);
        assertThat(decision.detail()).contains("id");
    }

    @Test
    void unresolvedRequiredQueryParametersSkipTheEndpoint() {
        EndpointDescriptor endpoint = endpoint("GET", "/reports",
                new ParameterDescriptor("from", "query", true, operation("{}")),
                new ParameterDescriptor("page", "query", false, operation("{}")));

        ProbeDecision decision = policy(enabled(), noParameters()).decide(endpoint, 0);

        assertThat(decision.skipReason()).isEqualTo(SkipReason.MISSING_REQUIRED_PARAM);
        assertThat(decision.detail()).contains("from").doesNotContain("page");
    }

    @Test
    void optionalQueryParametersAreLeftOutRatherThanGuessed() {
        EndpointDescriptor endpoint = endpoint("GET", "/reports",
                new ParameterDescriptor("page", "query", false, operation("""
                        {"name":"page","in":"query","example":"7"}""")));

        ProbeDecision decision = policy(enabled(),
                new ParameterResolution(List.of(new SpecExampleParameterResolver()))).decide(endpoint, 0);

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.parameters().query()).isEmpty();
    }

    @Test
    void theEndpointBudgetCapsAGeneratedDocument() {
        ProbeProperties properties = new ProbeProperties(true, null, null, null, null, null, 2,
                null, null, null, null);

        assertThat(policy(properties, noParameters()).decide(get("/orders"), 1).allowed()).isTrue();
        assertThat(policy(properties, noParameters()).decide(get("/orders"), 2).skipReason())
                .isEqualTo(SkipReason.MAX_ENDPOINTS_REACHED);
    }

    @Test
    void configuredValuesAreRecordedWithTheirSourceButNeverTheValue() {
        ProbeDecision decision = policy(enabled(), configured(Map.of("id", "ord-1")))
                .decide(endpoint("GET", "/orders/{id}", pathParameter("id")), 0);

        assertThat(decision.parameters().sources()).containsEntry("path:id", "CONFIG");
        assertThat(decision.parameters().path()).containsEntry("id", "ord-1");
    }

    private ProbePolicy policy(ProbeProperties properties, ParameterResolution parameters) {
        return new ProbePolicy(properties, parameters);
    }

    private ProbeProperties defaults() {
        return new ProbeProperties(null, null, null, null, null, null, null, null, null, null, null);
    }

    private ProbeProperties enabled() {
        return new ProbeProperties(true, null, null, null, null, null, null, null, null, null, null);
    }

    private ProbeProperties unsafe(Set<String> methods, List<String> includePaths, boolean allowDelete) {
        return new ProbeProperties(true, null, null, null, null, null, null, null, null, null,
                new UnsafeProperties(true, methods, includePaths, allowDelete));
    }

    private ParameterResolution noParameters() {
        return new ParameterResolution(List.of());
    }

    private ParameterResolution configured(Map<String, String> defaults) {
        return new ParameterResolution(List.of(
                new ConfiguredParameterResolver(new ParameterProperties(false, defaults, Map.of()))));
    }

    private EndpointDescriptor get(String path) {
        return endpoint("GET", path);
    }

    private EndpointDescriptor get(String path, JsonNode operation) {
        return new EndpointDescriptor(ArtifactWriter.safeFileName("get" + path), "GET", path,
                null, null, List.of(), false, List.of(), operation);
    }

    private EndpointDescriptor endpoint(String method, String path, ParameterDescriptor... parameters) {
        return new EndpointDescriptor(ArtifactWriter.safeFileName(method + path), method, path,
                null, null, List.of(), false, List.of(parameters), operation("{}"));
    }

    private ParameterDescriptor pathParameter(String name) {
        return new ParameterDescriptor(name, "path", true, operation("{}"));
    }

    private JsonNode operation(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
