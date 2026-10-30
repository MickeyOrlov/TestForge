package io.testforge.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(new DataProperties(0));

    @Test
    void replacesVariablesAndLeavesTheRestUntouched() {
        assertThat(renderer.render("id=%{id}%, name=%{name}%", Map.of("id", 7, "name", "x")))
                .isEqualTo("id=7, name=x");
    }

    @Test
    void theSameVariableMayAppearMoreThanOnce() {
        assertThat(renderer.render("%{a}%-%{a}%", Map.of("a", "v"))).isEqualTo("v-v");
    }

    @Test
    void valuesAreExpandedRecursively() {
        assertThat(renderer.render("%{greeting}%", Map.of("greeting", "hi %{name}%", "name", "bob")))
                .isEqualTo("hi bob");
    }

    @Test
    void cyclesAreReportedWithTheChain() {
        assertThatThrownBy(() -> renderer.render("%{a}%", Map.of("a", "%{b}%", "b", "%{a}%")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic")
                .hasMessageContaining("[a, b] -> a");
    }

    @Test
    void missingVariableIsNamed() {
        assertThatThrownBy(() -> renderer.render("%{nope}%", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nope");
    }

    @Test
    void malformedPlaceholdersAreRejected() {
        assertThatThrownBy(() -> renderer.render("x %{open", Map.of()))
                .hasMessageContaining("Unclosed");
        assertThatThrownBy(() -> renderer.render("x %{ }%", Map.of()))
                .hasMessageContaining("must not be blank");
    }

    @Test
    void depthLimitStopsRunawayExpansionThatIsNotACycle() {
        TemplateRenderer shallow = new TemplateRenderer(new DataProperties(2));
        Map<String, String> chain = Map.of("a", "%{b}%", "b", "%{c}%", "c", "%{d}%", "d", "end");

        assertThat(new TemplateRenderer(new DataProperties(5)).render("%{a}%", chain)).isEqualTo("end");
        assertThatThrownBy(() -> shallow.render("%{a}%", chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max depth: 2");
    }

    @Test
    void resolverFunctionIsConsultedLazily() {
        assertThat(renderer.render("%{x}%", name -> Optional.of(name.toUpperCase()))).isEqualTo("X");
        assertThat(renderer.render(null, Map.of())).isNull();
    }

    @Test
    void defaultDepthIsTen() {
        assertThat(new DataProperties(0).maxTemplateDepth()).isEqualTo(10);
    }
}
