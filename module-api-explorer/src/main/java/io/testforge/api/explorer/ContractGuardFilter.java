package io.testforge.api.explorer;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Hands every response {@code ApiClient} receives from the documented API to
 * the {@link ContractGuard}. Outermost in the chain, so it sees the final
 * response after any retry. The document describes the API at
 * {@code forge.http.base-url}; a call to another service of the same client
 * ({@code request("payments")}, a downstream mock) is left alone.
 * A guard that breaks must never break a request: checking errors are logged
 * and swallowed; only {@code mode: fail} turns a mismatch into a test failure.
 */
public class ContractGuardFilter implements OrderedFilter {

    static final int ORDER = 10;

    private final ContractGuard guard;
    private final ContractGuardProperties.Mode mode;
    private final URI api;

    public ContractGuardFilter(ContractGuard guard, ContractGuardProperties.Mode mode) {
        this(guard, mode, null);
    }

    /**
     * @param apiBaseUrl {@code forge.http.base-url}; {@code null} checks every
     *                   response, whatever service it came from
     */
    public ContractGuardFilter(ContractGuard guard, ContractGuardProperties.Mode mode, String apiBaseUrl) {
        this.guard = guard;
        this.mode = mode;
        this.api = apiBaseUrl == null || apiBaseUrl.isBlank() ? null : URI.create(apiBaseUrl);
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {
        Response response = ctx.next(requestSpec, responseSpec);
        if (api != null && !documented(URI.create(requestSpec.getURI()), api)) {
            return response;
        }
        List<ContractGuard.Finding> findings;
        try {
            findings = guard.check(requestSpec.getMethod(), relativePath(requestSpec), exchange(response));
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ContractGuardFilter.class)
                    .debug("Contract guard could not check {}: {}", requestSpec.getURI(), e.toString());
            return response;
        }
        if (mode == ContractGuardProperties.Mode.FAIL && !findings.isEmpty()) {
            throw new AssertionError("Response of " + requestSpec.getMethod() + " " + relativePath(requestSpec)
                    + " does not match the OpenAPI document:\n" + findings.stream()
                    .map(finding -> "  - " + finding.kind() + " at " + finding.location() + ": " + finding.detail())
                    .collect(Collectors.joining("\n")));
        }
        return response;
    }

    /** The request path minus the base URI's own path, so it lines up with the document's paths. */
    static String relativePath(FilterableRequestSpecification requestSpec) {
        String path = URI.create(requestSpec.getURI()).getRawPath();
        String base = URI.create(requestSpec.getBaseUri()).getRawPath() + nullToEmpty(requestSpec.getBasePath());
        base = base.replaceAll("/+$", "");
        if (!base.isEmpty() && path.startsWith(base)) {
            path = path.substring(base.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    /** Same scheme, host and port as the API, under its base path. */
    static boolean documented(URI request, URI api) {
        return equalsIgnoreCase(request.getScheme(), api.getScheme())
                && equalsIgnoreCase(request.getHost(), api.getHost())
                && port(request) == port(api)
                && nullToEmpty(request.getRawPath()).startsWith(nullToEmpty(api.getRawPath()).replaceAll("/+$", ""));
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static RuntimeExchange exchange(Response response) {
        Map<String, String> headers = new LinkedHashMap<>();
        response.getHeaders().forEach(header -> headers.put(header.getName(), header.getValue()));
        return new RuntimeExchange(Map.of(), null, response.getStatusCode(), response.getContentType(), headers,
                response.asString(), response.getTime(), null);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
