package io.testforge.api.explorer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import com.github.tomakehurst.wiremock.matching.RequestPattern;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks what a WireMock stub answers against the downstream's OpenAPI
 * document, with the same matching and checks as the contract guard. A stub
 * that returns a field the real service never sends, or misses one it always
 * does, makes a test pass against a service that does not exist.
 *
 * <pre>{@code
 * stubContract.assertHonoured(wireMock);                      // every stub on the server
 * stubContract.assertHonoured(scope.stub(post(...).willReturn(...)));   // one scoped stub
 * }</pre>
 *
 * Only what a stub states can be checked: a stub matched by a URL regex, a
 * response from a body file, a fault or a templated body is reported as not
 * checked, never as a pass.
 */
public class StubContractChecker {

    /** One stub and what is wrong with it; {@code kind} is a mismatch kind, UNDOCUMENTED_OPERATION or NOT_CHECKED. */
    public record StubFinding(String stub, String kind, String location, String detail) {

        public boolean problem() {
            return !NOT_CHECKED.equals(kind);
        }
    }

    static final String NOT_CHECKED = "NOT_CHECKED";

    private final ContractGuard guard;
    private final String stripPrefix;

    public StubContractChecker(OpenApiSpecParser parser, ApiDiscoveryProperties discovery,
                               StubContractProperties properties) {
        this.guard = new ContractGuard(parser, new OperationSelector(), new ResponseContractChecker(new ObjectMapper()),
                discovery, new ContractGuardProperties(true, ContractGuardProperties.Mode.RECORD, properties.specs()));
        this.stripPrefix = properties.stripPrefix();
    }

    /** Problems and unchecked parts of one stub; empty when it honours the document. */
    public List<StubFinding> check(StubMapping stub) {
        String name = describe(stub);
        RequestPattern request = stub.getRequest();
        ResponseDefinition response = stub.getResponse();
        String path = request == null ? null : exactPath(request);
        if (request == null || request.getMethod() == null || "ANY".equals(request.getMethod().getName())) {
            return List.of(notChecked(name, "no fixed method"));
        }
        if (path == null) {
            return List.of(notChecked(name, "matched by a URL pattern, not a path"));
        }
        if (response == null || response.getFault() != null) {
            return List.of(notChecked(name, "answers with a fault, not a response"));
        }
        if (response.getBodyFileName() != null) {
            return List.of(notChecked(name, "body comes from a file"));
        }
        String body = body(response);
        if (body != null && body.contains("{{")) {
            return List.of(notChecked(name, "templated body"));
        }
        String contentType = response.getHeaders() == null || !response.getHeaders().getContentTypeHeader().isPresent()
                ? (body == null ? null : "application/json")
                : response.getHeaders().getContentTypeHeader().firstValue();
        RuntimeExchange exchange = new RuntimeExchange(Map.of(), null, response.getStatus(), contentType, Map.of(),
                body, 0, null);
        List<StubFinding> findings = new ArrayList<>();
        for (ContractGuard.Finding finding : guard.check(request.getMethod().getName(), strip(path), exchange)) {
            findings.add(new StubFinding(name, finding.kind(), finding.location(), finding.detail()));
        }
        return findings;
    }

    /** Every stub the server has right now: the environment's defaults and any open scope's. */
    public List<StubFinding> checkAll(WireMock wireMock) {
        List<StubFinding> findings = new ArrayList<>();
        wireMock.allStubMappings().getMappings().forEach(stub -> findings.addAll(check(stub)));
        return findings;
    }

    /** Fails with every stub that contradicts the document; unchecked stubs are listed, not failed. */
    public void assertHonoured(WireMock wireMock) {
        fail(checkAll(wireMock));
    }

    public void assertHonoured(StubMapping stub) {
        fail(check(stub));
    }

    private static void fail(List<StubFinding> findings) {
        List<StubFinding> problems = findings.stream().filter(StubFinding::problem).toList();
        if (problems.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder(problems.size() + " stub findings: a stub answers what the "
                + "real service would not");
        problems.forEach(finding -> message.append("\n  - ").append(finding.stub()).append(": ")
                .append(finding.kind()).append(" at ").append(finding.location()).append(" — ")
                .append(finding.detail()));
        List<StubFinding> unchecked = findings.stream().filter(finding -> !finding.problem()).toList();
        if (!unchecked.isEmpty()) {
            message.append("\nNot checked: ");
            unchecked.forEach(finding -> message.append(finding.stub()).append(" (").append(finding.detail())
                    .append("); "));
        }
        throw new AssertionError(message.toString());
    }

    private String strip(String path) {
        if (stripPrefix != null && path.startsWith(stripPrefix)) {
            String rest = path.substring(stripPrefix.length());
            return rest.isEmpty() ? "/" : rest;
        }
        return path;
    }

    private static String exactPath(RequestPattern request) {
        if (request.getUrlPath() != null) {
            return request.getUrlPath();
        }
        if (request.getUrl() != null) {
            int query = request.getUrl().indexOf('?');
            return query < 0 ? request.getUrl() : request.getUrl().substring(0, query);
        }
        return null;
    }

    private static String body(ResponseDefinition response) {
        if (response.getBody() != null) {
            return response.getBody();
        }
        return response.getJsonBody() == null ? null : response.getJsonBody().toString();
    }

    private static StubFinding notChecked(String stub, String why) {
        return new StubFinding(stub, NOT_CHECKED, "$", why);
    }

    private static String describe(StubMapping stub) {
        RequestPattern request = stub.getRequest();
        String method = request == null || request.getMethod() == null ? "ANY" : request.getMethod().getName();
        String url = request == null ? "?" : java.util.stream.Stream.of(request.getUrl(), request.getUrlPath(),
                        request.getUrlPattern(), request.getUrlPathPattern())
                .filter(java.util.Objects::nonNull).findFirst().orElse("(any url)");
        int status = stub.getResponse() == null ? 0 : stub.getResponse().getStatus();
        return method + " " + url + " → " + status;
    }
}
