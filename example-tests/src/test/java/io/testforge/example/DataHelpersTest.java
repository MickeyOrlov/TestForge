package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.data.Generators;
import io.testforge.data.RunUniqueValues;
import io.testforge.data.TemplateRenderer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-data: generated values are guarded for run uniqueness,
 * and payload templates can reference scenario variables with readable errors.
 */
@SpringBootTest
class DataHelpersTest {

    @Autowired
    RunUniqueValues uniqueValues;

    @Autowired
    TemplateRenderer templates;

    @BeforeEach
    void clearUniqueValues() {
        uniqueValues.clear();
    }

    @Test
    void generatesValueUntilItIsUniqueForCurrentRun() {
        AtomicInteger attempts = new AtomicInteger();

        assertThat(uniqueValues.tryRegister("phone", "5550001")).isTrue();
        String value = uniqueValues.generate(
                "phone",
                () -> attempts.incrementAndGet() == 1 ? "5550001" : "5550002",
                3);

        assertThat(value).isEqualTo("5550002");
        assertThat(uniqueValues.snapshot().get("phone"))
                .containsExactlyInAnyOrder("5550001", "5550002");
    }

    @Test
    void generatorPresetsProduceMaskedUniqueValues() {
        String phone = uniqueValues.generate("phone", Generators.phone("+8499", 7), 10);
        String guid = uniqueValues.generate("guid", Generators.guid(), 10);

        assertThat(phone).matches("\\+8499[1-9]\\d{6}");
        assertThat(guid).matches("[0-9a-f-]{36}");
    }

    @Test
    void rendersNestedScenarioVariables() {
        Map<String, Object> variables = Map.of(
                "requestId", "req-42",
                "suffix", "%{requestId}%-accepted");

        assertThat(templates.render("event:%{suffix}%", variables))
                .isEqualTo("event:req-42-accepted");
    }

    @Test
    void detectsMissingAndCyclicVariables() {
        assertThatThrownBy(() -> templates.render("%{absent}%", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent");

        assertThatThrownBy(() -> templates.render("%{a}%", Map.of("a", "%{b}%", "b", "%{a}%")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic template variable reference");
    }
}
