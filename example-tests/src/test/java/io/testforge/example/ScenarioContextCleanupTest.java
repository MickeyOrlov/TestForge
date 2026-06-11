package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.context.ContextKey;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioContextExtension;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Demonstrates ScenarioContextExtension: worker threads are reused between
 * tests, so without the extension a value left in the thread-local context
 * by one test leaks into the next. No Spring needed — plain JUnit.
 */
@ExtendWith(ScenarioContextExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScenarioContextCleanupTest {

    private static final ContextKey<String> LEFTOVER = ContextKey.of("LEFTOVER", String.class);

    @Test
    @Order(1)
    void firstTestLeavesValueBehind() {
        ScenarioContext.put(LEFTOVER, "dirty");

        assertThat(ScenarioContext.find(LEFTOVER)).contains("dirty");
    }

    @Test
    @Order(2)
    void extensionClearedContextBetweenTests() {
        assertThat(ScenarioContext.find(LEFTOVER)).isEmpty();
    }

    @Test
    @Order(3)
    void scopedBlockGetsIsolatedContext() {
        ScenarioContext.put(LEFTOVER, "outer");

        ScenarioContext.runScoped(() -> {
            // fresh store inside the scope, the outer value is not visible
            assertThat(ScenarioContext.find(LEFTOVER)).isEmpty();

            ScenarioContext.put(LEFTOVER, "inner");
            assertThat(ScenarioContext.get(LEFTOVER)).isEqualTo("inner");
        });

        // the surrounding thread-local store is untouched by the scope
        assertThat(ScenarioContext.get(LEFTOVER)).isEqualTo("outer");
    }
}
