package io.testforge.data.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;

class CleanupExtensionTest {

    @BeforeEach
    void reset() {
        CleanupTestkitFixture.RAN.clear();
        Leftovers.drain();
    }

    private static TestExecutionResult run(String method) {
        List<Event> finished = EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(CleanupTestkitFixture.class, method))
                .execute().testEvents().finished().list();
        assertThat(finished).hasSize(1);
        return finished.getFirst().getPayload(TestExecutionResult.class).orElseThrow();
    }

    @Test
    void childrenAreRemovedBeforeTheirParentsAndClassDataLast() {
        assertThat(run("createsParentThenChild").getStatus()).isEqualTo(TestExecutionResult.Status.SUCCESSFUL);

        assertThat(CleanupTestkitFixture.RAN).containsExactly("order line", "order", "class-level tenant");
    }

    @Test
    void aFailedTestIsStillCleanedUpAndKeepsItsOwnFailure() {
        TestExecutionResult result = run("failsHalfway");

        assertThat(result.getStatus()).isEqualTo(TestExecutionResult.Status.FAILED);
        assertThat(result.getThrowable().orElseThrow()).hasMessageContaining("order status");
        assertThat(CleanupTestkitFixture.RAN).contains("half-created order");
    }

    @Test
    void leftoverDataFailsAPassingTestAndTheOtherActionsStillRun() {
        TestExecutionResult result = run("passesButCannotCleanUp");

        assertThat(result.getStatus()).isEqualTo(TestExecutionResult.Status.FAILED);
        assertThat(result.getThrowable().orElseThrow())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("left data behind")
                .hasMessageContaining("locked order: java.lang.IllegalStateException: 409 order is locked");
        assertThat(CleanupTestkitFixture.RAN).containsSubsequence("last", "first");
        assertThat(Leftovers.recorded()).singleElement().satisfies(leftover -> {
            assertThat(leftover.test()).isEqualTo("CleanupTestkitFixture > passesButCannotCleanUp()");
            assertThat(leftover.description()).isEqualTo("locked order");
            assertThat(leftover.error()).isEqualTo("java.lang.IllegalStateException: 409 order is locked");
            assertThat(leftover.testFailed()).isFalse();
        });
    }

    @Test
    void aCleanupProblemIsAttachedToTheTestsFailureNotInstead() {
        Throwable failure = run("failsAndCannotCleanUp").getThrowable().orElseThrow();

        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage("the test's own failure");
        assertThat(failure.getSuppressed()).singleElement().satisfies(suppressed ->
                assertThat(suppressed).hasMessageContaining("cleanup failed: locked order")
                        .hasRootCauseMessage("409 order is locked"));
        assertThat(Leftovers.recorded()).singleElement()
                .satisfies(leftover -> assertThat(leftover.testFailed()).isTrue());
    }

    @Test
    void aCleanTestRecordsNoLeftovers() {
        run("createsParentThenChild");

        assertThat(Leftovers.recorded()).isEmpty();
    }

    @Test
    void theWholeFixtureLeavesNothingPendingOnTheThread() {
        EngineTestKit.engine("junit-jupiter").selectors(selectClass(CleanupTestkitFixture.class)).execute();

        assertThat(Cleanup.pending()).isEmpty();
        assertThat(Cleanup.depth()).isEqualTo(1);
    }

    @Test
    void registrationsArePerThread() throws Exception {
        List<String> seenElsewhere = new CopyOnWriteArrayList<>();
        Cleanup.openScope();
        try {
            Cleanup.register("mine", () -> { });
            Thread other = new Thread(() -> seenElsewhere.addAll(Cleanup.pending()));
            other.start();
            other.join();

            assertThat(Cleanup.pending()).containsExactly("mine");
            assertThat(seenElsewhere).isEmpty();
        } finally {
            Cleanup.closeScope();
        }
    }
}
