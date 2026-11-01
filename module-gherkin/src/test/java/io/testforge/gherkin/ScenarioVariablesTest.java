package io.testforge.gherkin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.core.context.ScenarioContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScenarioVariablesTest {

    private final ScenarioVariables variables = new ScenarioVariables();

    @AfterEach
    void clear() {
        ScenarioContext.clear();
    }

    @Test
    void rememberedValuesAreResolvedInLaterSteps() {
        variables.remember("orderId", "o-1");
        variables.remember("item", "a$b");

        assertThat(variables.resolve("/orders/${orderId}/items/${item}")).isEqualTo("/orders/o-1/items/a$b");
        assertThat(variables.resolve("no references")).isEqualTo("no references");
    }

    @Test
    void anUnknownNameSaysWhatTheScenarioDidRemember() {
        variables.remember("orderId", "o-1");

        assertThatThrownBy(() -> variables.resolve("/orders/${orderID}"))
                .hasMessage("No scenario variable 'orderID'; this scenario remembered [orderId]");
    }

    @Test
    void variablesEndWithTheScenario() {
        variables.remember("orderId", "o-1");
        ScenarioContext.clear();

        assertThatThrownBy(() -> variables.get("orderId")).hasMessageContaining("remembered []");
    }

    @Test
    void scenariosGetAScopeAndCorrelationIdByDefault() {
        GherkinProperties defaults = new GherkinProperties(null, null, null);

        assertThat(defaults.scopePerScenario()).isTrue();
        assertThat(defaults.scopePrefix()).isEqualTo("bdd");
        assertThat(defaults.correlationPerScenario()).isTrue();
    }
}
