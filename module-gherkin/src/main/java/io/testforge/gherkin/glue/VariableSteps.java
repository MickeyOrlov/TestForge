package io.testforge.gherkin.glue;

import io.cucumber.java.en.Given;
import io.testforge.gherkin.ScenarioVariables;
import java.util.UUID;

/**
 * Data unique to this run, so scenarios on a shared environment never trip
 * over each other's orders, customers or e-mails.
 *
 * <pre>
 * Given I remember a unique value as "orderId"
 * When I send a GET request to "/orders/${orderId}"
 * </pre>
 */
public class VariableSteps {

    private final ScenarioVariables variables;

    public VariableSteps(ScenarioVariables variables) {
        this.variables = variables;
    }

    /** {@code <name>-1a2b3c4d5e6f}: readable in a log, unique per run. */
    @Given("I remember a unique value as {string}")
    public void unique(String name) {
        variables.remember(name, name + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    }

    @Given("I remember {string} as {string}")
    public void remember(String value, String name) {
        variables.remember(name, variables.resolve(value));
    }

    /** For a value a step remembered from a response or a message. */
    @io.cucumber.java.en.Then("the remembered {string} is {string}")
    public void is(String name, String expected) {
        String want = variables.resolve(expected);
        String actual = variables.get(name);
        if (!want.equals(actual)) {
            throw new AssertionError("remembered " + name + " is \"" + actual + "\", expected \"" + want + "\"");
        }
    }
}
