package io.testforge.core.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ScenarioContextTest {

    private static final ContextKey<String> ORDER = ContextKey.of("ORDER", String.class);
    private static final ContextKey<String> USER = ContextKey.of("USER", String.class);

    @AfterEach
    void clean() {
        ScenarioContext.clear();
    }

    @Test
    void oneValueCanBeDroppedWithoutClearingTheRest() {
        ScenarioContext.put(ORDER, "o-1");
        ScenarioContext.put(USER, "u-1");

        assertThat(ScenarioContext.remove(ORDER)).contains("o-1");
        assertThat(ScenarioContext.find(ORDER)).isEmpty();
        assertThat(ScenarioContext.get(USER)).isEqualTo("u-1");
        assertThat(ScenarioContext.remove(ORDER)).isEmpty();
    }

    @Test
    void aNullValuePointsAtRemove() {
        assertThatThrownBy(() -> ScenarioContext.put(ORDER, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("use remove(key)");
    }
}
