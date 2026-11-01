package io.testforge.example.gherkin;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import io.cucumber.java.en.Given;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.util.UUID;

/**
 * The project's own domain steps, next to TestForge's generic ones. Here they
 * stub the order service; in a real project "Given a paid order" drives the
 * product through module-state or module-flow.
 *
 * <p>Every stub matches this scenario's scope header only: two scenarios
 * running at once each get their own order back.
 */
public class OrderServiceSteps {

    @Given("the order service accepts new orders")
    public void acceptsNewOrders() {
        String scope = ScenarioContext.get(ScenarioKeys.TEST_SCOPE);
        String id = "o-" + UUID.randomUUID().toString().substring(0, 8);
        GherkinExampleContext.ORDER_SERVICE.stubFor(post(urlPathEqualTo("/orders")).atPriority(1)
                .withHeader("X-Test-Scope", equalTo(scope))
                .willReturn(okJson("{\"id\":\"" + id + "\",\"status\":\"NEW\"}").withStatus(201)));
        GherkinExampleContext.ORDER_SERVICE.stubFor(get(urlPathEqualTo("/orders/" + id)).atPriority(1)
                .withHeader("X-Test-Scope", equalTo(scope))
                .willReturn(okJson("{\"id\":\"" + id + "\",\"status\":\"NEW\",\"item\":\"book\"}")));
    }
}
