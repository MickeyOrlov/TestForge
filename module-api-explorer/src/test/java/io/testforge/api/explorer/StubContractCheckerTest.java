package io.testforge.api.explorer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StubContractCheckerTest {

    private static StubContractChecker checker(String stripPrefix) {
        ApiDiscoveryProperties discovery = new ApiDiscoveryProperties(true, null, null, null, null,
                Map.of(ExplorerFixtures.SPEC_ID, new ApiDiscoveryProperties.Spec(ExplorerFixtures.LOCATION)));
        return new StubContractChecker(new OpenApiSpecParser(), discovery,
                new StubContractProperties(true, List.of(), stripPrefix));
    }

    @Test
    void aStubThatAnswersLikeTheRealServiceHasNoFindings() {
        var stub = get(urlPathEqualTo("/tasks/t-1")).willReturn(okJson("{\"id\":\"t-1\",\"title\":\"Demo\"}")).build();

        assertThat(checker(null).check(stub)).isEmpty();
    }

    @Test
    void aStubMissingARequiredFieldIsAFinding() {
        var stub = get(urlPathEqualTo("/tasks/t-1")).willReturn(okJson("{\"id\":\"t-1\"}")).build();

        assertThat(checker(null).check(stub)).singleElement().satisfies(finding -> {
            assertThat(finding.stub()).isEqualTo("GET /tasks/t-1 → 200");
            assertThat(finding.kind()).isEqualTo("MISSING_REQUIRED_FIELD");
            assertThat(finding.location()).isEqualTo("$.title");
        });
    }

    @Test
    void aStubForAnOperationTheServiceDoesNotHaveIsAFinding() {
        var stub = get(urlPathEqualTo("/tasks/t-1/history")).willReturn(okJson("[]")).build();

        assertThat(checker(null).check(stub)).extracting(StubContractChecker.StubFinding::kind)
                .containsExactly(ContractGuard.UNDOCUMENTED_OPERATION);
    }

    @Test
    void aPrefixTheDocumentDoesNotKnowIsStripped() {
        var stub = get(urlPathEqualTo("/task-service/tasks/t-1"))
                .willReturn(okJson("{\"id\":\"t-1\",\"title\":\"Demo\"}")).build();

        assertThat(checker("/task-service").check(stub)).isEmpty();
    }

    @Test
    void whatAStubDoesNotStateIsReportedAsNotCheckedNeverAsAPass() {
        StubContractChecker checker = checker(null);

        assertThat(checker.check(get(urlPathMatching("/tasks/.*")).willReturn(okJson("{}")).build()))
                .extracting(StubContractChecker.StubFinding::detail)
                .containsExactly("matched by a URL pattern, not a path");
        assertThat(checker.check(get(urlPathEqualTo("/tasks/t-1"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).build()))
                .extracting(StubContractChecker.StubFinding::problem).containsExactly(false);
        assertThat(checker.check(get(urlPathEqualTo("/tasks/t-1"))
                .willReturn(okJson("{\"id\":\"{{request.path.[1]}}\"}")).build()))
                .extracting(StubContractChecker.StubFinding::detail).containsExactly("templated body");
    }

    @Test
    void everyStubOnTheServerCanBeHeldToTheDocumentAtOnce() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        server.start();
        try {
            WireMock wireMock = WireMock.create().host("localhost").port(server.port()).build();
            server.stubFor(get(urlPathEqualTo("/tasks/t-1")).willReturn(okJson("{\"id\":\"t-1\",\"title\":\"x\"}")));
            server.stubFor(get(urlPathEqualTo("/tasks/t-2")).willReturn(okJson("{\"id\":2,\"title\":\"x\"}")));
            server.stubFor(get(urlPathMatching("/tasks/.*")).atPriority(10).willReturn(okJson("{}")));

            assertThatThrownBy(() -> checker(null).assertHonoured(wireMock))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("1 stub findings")
                    .hasMessageContaining("GET /tasks/t-2 → 200: INCOMPATIBLE_FIELD_TYPE at $.id")
                    .hasMessageContaining("Not checked: GET /tasks/.* → 200 (matched by a URL pattern, not a path)");
        } finally {
            server.stop();
        }
    }
}
