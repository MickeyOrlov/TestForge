package io.testforge.gherkin.mock;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.request;
import static com.github.tomakehurst.wiremock.client.WireMock.requestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import io.cucumber.java.After;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.gherkin.ScenarioVariables;
import io.testforge.mock.MockScope;
import io.testforge.mock.ScopedMockClient;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Downstream stubs over {@link ScopedMockClient}: every stub belongs to this
 * scenario's scope — the one {@code ScenarioHooks} opened and
 * {@code module-http} sends — so parallel scenarios on one shared WireMock
 * never answer each other.
 *
 * <pre>
 * Given the mock answers GET "/stock/book" with 200:
 *   """
 *   {"item": "book", "available": 3}
 *   """
 * When I send a POST request to "/orders" with body: …
 * Then the mock received 1 GET request to "/stock/book"
 * </pre>
 *
 * The scope closes after the scenario; with
 * {@code forge.mock.fail-on-unused-stubs} a stub nobody called fails it.
 */
public class MockSteps {

    private final ObjectProvider<ScopedMockClient> mocks;
    private final ScenarioVariables variables;
    private MockScope scope;

    public MockSteps(ObjectProvider<ScopedMockClient> mocks, ScenarioVariables variables) {
        this.mocks = mocks;
        this.variables = variables;
    }

    @Given("the mock answers {word} {string} with {int}:")
    public void answers(String method, String path, int status, String body) {
        scope().stub(request(verb(method), urlPathEqualTo(variables.resolve(path)))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", contentType(body))
                        .withBody(variables.resolve(body))));
    }

    @Given("the mock answers {word} {string} with {int}")
    public void answers(String method, String path, int status) {
        scope().stub(request(verb(method), urlPathEqualTo(variables.resolve(path)))
                .willReturn(aResponse().withStatus(status)));
    }

    @Then("the mock received {int} {word} request(s) to {string}")
    public void received(int count, String method, String path) {
        scope().verify(count, requestedFor(verb(method), urlPathEqualTo(variables.resolve(path))));
    }

    @Then("the mock received no {word} request to {string}")
    public void receivedNone(String method, String path) {
        received(0, method, path);
    }

    /** Before ScenarioHooks clears the context (After hooks run highest order first). */
    @After(order = 100)
    public void closeScope() {
        if (scope != null) {
            MockScope open = scope;
            scope = null;
            open.close();
        }
    }

    private MockScope scope() {
        if (scope == null) {
            ScopedMockClient client = mocks.getIfAvailable();
            if (client == null) {
                throw new IllegalStateException("No ScopedMockClient: set forge.mock.base-url to the shared WireMock");
            }
            scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE).map(client::scope).orElseGet(client::scope);
        }
        return scope;
    }

    private static String verb(String method) {
        return method.toUpperCase(Locale.ROOT);
    }

    private static String contentType(String body) {
        String text = body == null ? "" : body.strip();
        return text.startsWith("{") || text.startsWith("[") ? "application/json" : "text/plain";
    }
}
