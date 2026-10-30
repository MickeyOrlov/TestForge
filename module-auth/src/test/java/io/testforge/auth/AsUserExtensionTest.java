package io.testforge.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@AsUser("member")
class AsUserExtensionTest {

    @AfterAll
    static void nothingLeaksAfterTheClass() {
        assertThat(AuthContext.currentRole()).isEmpty();
    }

    @Test
    void classAnnotationApplies() {
        assertThat(AuthContext.currentRole()).contains("member");
    }

    @Test
    @AsUser("admin")
    void methodAnnotationWins() {
        assertThat(AuthContext.currentRole()).contains("admin");
    }

    @Test
    void runAsSwitchesForABlockAndRestores() {
        String inside = AuthContext.callAs("auditor", () -> AuthContext.currentRole().orElseThrow());

        assertThat(inside).isEqualTo("auditor");
        assertThat(AuthContext.currentRole()).contains("member");
    }

    @Nested
    class NestedTests {

        @Test
        void inheritsTheEnclosingRole() {
            assertThat(AuthContext.currentRole()).contains("member");
        }
    }
}
