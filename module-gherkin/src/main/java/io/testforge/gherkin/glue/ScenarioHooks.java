package io.testforge.gherkin.glue;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.Scenario;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import io.testforge.gherkin.GherkinProperties;
import java.util.UUID;

/**
 * Opens every scenario the way a TestForge JUnit test starts: an empty
 * {@link ScenarioContext}, a scope of its own (so {@code module-http} tags its
 * requests and {@code module-mock} matches only this scenario's stubs) and a
 * correlation id. Both are logged to the scenario, so a report shows which
 * scope to search the logs for.
 */
public class ScenarioHooks {

    private final GherkinProperties properties;

    public ScenarioHooks(GherkinProperties properties) {
        this.properties = properties;
    }

    @Before(order = 0)
    public void openScenario(Scenario scenario) {
        ScenarioContext.clear();
        if (properties.scopePerScenario()) {
            String scope = properties.scopePrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);
            ScenarioContext.put(ScenarioKeys.TEST_SCOPE, scope);
            scenario.log("test scope: " + scope);
        }
        if (properties.correlationPerScenario()) {
            String correlation = UUID.randomUUID().toString();
            ScenarioContext.put(ScenarioKeys.CORRELATION_ID, correlation);
            scenario.log("correlation id: " + correlation);
        }
    }

    /** After hooks run highest order first: this one runs after every other. */
    @After(order = 0)
    public void closeScenario() {
        ScenarioContext.clear();
    }
}
