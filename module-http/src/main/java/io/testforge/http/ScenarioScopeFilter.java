package io.testforge.http;

import io.restassured.filter.FilterContext;
import io.restassured.filter.OrderedFilter;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Closes the correlation loop between {@code module-mock} and outgoing
 * requests: the scope id the scenario opened is written into the request body
 * at the very path the scoped stubs match on.
 *
 * <p>Without this filter every test has to remember to embed the id into each
 * payload by hand — and a forgotten one does not fail, it silently hits the
 * shared default stub instead.
 *
 * <p>The filter is a no-op unless the scenario actually opened a scope. Tests
 * that never touch the mock server keep sending untouched payloads: this
 * module must not reshape traffic to the real system under test on its own
 * initiative.
 */
public class ScenarioScopeFilter implements OrderedFilter {

    static final int ORDER = 200;

    private static final Logger log = LoggerFactory.getLogger("forge.http");

    private final JsonScopeWriter writer;
    private final String jsonPath;
    private final String header;

    public ScenarioScopeFilter(JsonScopeWriter writer, String jsonPath, String header) {
        this.writer = writer;
        this.jsonPath = jsonPath;
        this.header = header;
    }

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        Optional<String> scopeId = ScenarioContext.find(ScenarioKeys.TEST_SCOPE);
        if (scopeId.isEmpty()) {
            return ctx.next(requestSpec, responseSpec);
        }

        if (header != null && !requestSpec.getHeaders().hasHeaderWithName(header)) {
            requestSpec.header(header, scopeId.get());
        }
        if (jsonPath != null) {
            applyToBody(requestSpec, scopeId.get());
        }

        return ctx.next(requestSpec, responseSpec);
    }

    private void applyToBody(FilterableRequestSpecification requestSpec, String scopeId) {
        if (!looksLikeJson(requestSpec.getContentType())) {
            return;
        }

        Object body = requestSpec.getBody();
        String text = switch (body) {
            case null -> null;
            case String string -> string;
            case byte[] bytes -> new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            default -> null;
        };

        if (text == null) {
            log.debug("Scope {} not embedded in the body of {} {}: body is {}, send it as a JSON string or use "
                            + "forge.http.scope.header", scopeId, requestSpec.getMethod(), requestSpec.getURI(),
                    body == null ? "empty" : body.getClass().getSimpleName());
            return;
        }

        writer.write(text, jsonPath, scopeId).ifPresent(requestSpec::body);
    }

    private static boolean looksLikeJson(String contentType) {
        // an unset content type is common for bodies built as raw strings; let
        // the writer decide by trying to parse
        return contentType == null || contentType.toLowerCase(Locale.ROOT).contains("json");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
