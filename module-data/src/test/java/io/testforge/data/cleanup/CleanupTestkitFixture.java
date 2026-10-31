package io.testforge.data.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Run only by {@link CleanupExtensionTest} through EngineTestKit; several tests fail on purpose. */
@ExtendWith(CleanupExtension.class)
class CleanupTestkitFixture {

    static final List<String> RAN = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void classData() {
        Cleanup.register("class-level tenant", () -> RAN.add("class-level tenant"));
    }

    @Test
    void createsParentThenChild() {
        Cleanup.register("order", () -> RAN.add("order"));
        Cleanup.register("order line", () -> RAN.add("order line"));
    }

    @Test
    void failsHalfway() {
        Cleanup.register("half-created order", () -> RAN.add("half-created order"));
        assertThat("PAID").as("order status").isEqualTo("NEW");
    }

    @Test
    void passesButCannotCleanUp() {
        Cleanup.register("first", () -> RAN.add("first"));
        Cleanup.register("locked order", () -> {
            throw new IllegalStateException("409 order is locked");
        });
        Cleanup.register("last", () -> RAN.add("last"));
    }

    @Test
    void failsAndCannotCleanUp() {
        Cleanup.register("locked order", () -> {
            throw new IllegalStateException("409 order is locked");
        });
        throw new IllegalArgumentException("the test's own failure");
    }
}
