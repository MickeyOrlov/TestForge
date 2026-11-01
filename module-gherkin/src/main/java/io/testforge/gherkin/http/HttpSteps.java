package io.testforge.gherkin.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.testforge.gherkin.JsonFields;
import io.testforge.gherkin.ScenarioVariables;
import io.testforge.http.ApiClient;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP steps over {@link ApiClient}, so a scenario's requests carry what every
 * TestForge request carries — base URL per environment, the scenario's scope
 * and correlation id, authentication, redacted logging.
 *
 * <pre>
 * When I send a POST request to "/orders" with body:
 *   """
 *   {"item": "book"}
 *   """
 * Then the response status is 201
 * And I remember the response field "$.id" as "orderId"
 * When I send a GET request to "/orders/${orderId}"
 * Then the response field "$.status" is "NEW"
 * </pre>
 *
 * Paths, headers and bodies resolve {@code ${name}} from
 * {@link ScenarioVariables}. A failure names the request; bodies are in the
 * {@code module-http} log, redacted, not in the message.
 */
public class HttpSteps {

    private final ApiClient api;
    private final ScenarioVariables variables;
    private final ObjectMapper objectMapper;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private Response response;
    private String request;

    public HttpSteps(ApiClient api, ScenarioVariables variables, ObjectMapper objectMapper) {
        this.api = api;
        this.variables = variables;
        this.objectMapper = objectMapper;
    }

    @Given("the request header {string} is {string}")
    public void header(String name, String value) {
        headers.put(name, variables.resolve(value));
    }

    @When("I send a {word} request to {string}")
    public void send(String method, String path) {
        send(method, path, null);
    }

    @When("I send a {word} request to {string} with body:")
    public void send(String method, String path, String body) {
        String verb = method.toUpperCase(Locale.ROOT);
        String resolved = variables.resolve(path);
        RequestSpecification spec = api.request().headers(headers);
        if (body != null) {
            String text = variables.resolve(body);
            String trimmed = text.strip();
            spec = spec.body(text);
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                spec = spec.contentType(ContentType.JSON);
            }
        }
        request = verb + " " + resolved;
        response = spec.request(verb, resolved);
    }

    @Then("the response status is {int}")
    public void status(int expected) {
        int actual = response().statusCode();
        if (actual != expected) {
            throw new AssertionError(request + " answered " + actual + ", expected " + expected
                    + " (the body is in the forge.http log)");
        }
    }

    @Then("the response field {string} is {string}")
    public void fieldIs(String path, String expected) {
        String want = variables.resolve(expected);
        String actual = field(path);
        if (!want.equals(actual)) {
            throw new AssertionError(request + ": response field " + path + " is \"" + actual + "\", expected \""
                    + want + "\"");
        }
    }

    @Then("the response field {string} contains {string}")
    public void fieldContains(String path, String fragment) {
        String want = variables.resolve(fragment);
        String actual = field(path);
        if (!actual.contains(want)) {
            throw new AssertionError(request + ": response field " + path + " is \"" + actual
                    + "\", expected it to contain \"" + want + "\"");
        }
    }

    @Then("I remember the response field {string} as {string}")
    public void remember(String path, String name) {
        variables.remember(name, field(path));
    }

    private String field(String path) {
        return JsonFields.read(response().asString(), path, request, objectMapper);
    }

    private Response response() {
        if (response == null) {
            throw new IllegalStateException("No request was sent in this scenario yet: send one with "
                    + "'I send a GET request to \"/path\"' first");
        }
        return response;
    }
}
