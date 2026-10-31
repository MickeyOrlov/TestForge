package io.testforge.data.isolation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Event;

class DataIsolationExtensionTest {

    @BeforeEach
    void reset() {
        DataIsolationTestkitFixture.SEEN.clear();
        DataIsolationTestkitFixture.UNDONE.clear();
        DataIsolation.configure("tf", "run1");
    }

    @AfterEach
    void restore() {
        DataIsolation.configure(null, null);
    }

    private static TestExecutionResult run(String method) {
        List<Event> finished = EngineTestKit.engine("junit-jupiter")
                .selectors(selectMethod(DataIsolationTestkitFixture.class, method))
                .execute().testEvents().finished().list();
        assertThat(finished).hasSize(1);
        return finished.getFirst().getPayload(TestExecutionResult.class).orElseThrow();
    }

    @Test
    void eachTestGetsItsOwnTagAndItIsClosedAfterwards() {
        run("first");
        run("second");

        assertThat(DataIsolationTestkitFixture.SEEN).hasSize(2).doesNotHaveDuplicates()
                .allMatch(tag -> tag.matches("tf-run1-t\\d+"));
        assertThat(DataIsolation.scopeTag()).isEqualTo("tf-run1");
        assertThat(DataIsolation.testTag()).isEmpty();
    }

    @Test
    void createdDataIsTaggedAndUndoneInsideTheSameTest() {
        assertThat(run("createsAnOrder").getStatus()).isEqualTo(TestExecutionResult.Status.SUCCESSFUL);

        String reference = DataIsolationTestkitFixture.SEEN.getFirst();
        assertThat(reference).matches("tf-run1-t\\d+-order-\\d+");
        String tag = reference.substring(0, reference.indexOf("-order-"));
        assertThat(DataIsolationTestkitFixture.UNDONE).containsExactly(reference + " undone in " + tag);
    }

    @Test
    void nothingIsRegisteredWhenPersistingFails() {
        TestExecutionResult result = run("persistingFails");

        assertThat(result.getThrowable().orElseThrow()).hasMessage("backend down").hasNoSuppressedExceptions();
        assertThat(DataIsolationTestkitFixture.UNDONE).isEmpty();
    }

    @Test
    void ownsMatchesTheWholeTagNotAPrefixOfAnother() {
        DataIsolation.openTest();
        try {
            String mine = DataIsolation.tag("anna") + "@example.test";
            String sibling = DataIsolation.runTag() + "-t" + Long.MAX_VALUE + "-bob";
            String longer = DataIsolation.scopeTag() + "7-carl";

            assertThat(DataIsolation.owns(mine)).isTrue();
            assertThat(DataIsolation.owns("Order " + mine.toUpperCase())).as("case and position do not matter").isTrue();
            assertThat(DataIsolation.owns(sibling)).isFalse();
            assertThat(DataIsolation.owns(longer)).as("t1 must not own t17").isFalse();
            assertThat(DataIsolation.ownedByRun(sibling)).isTrue();
            assertThat(DataIsolation.owns(null)).isFalse();
        } finally {
            DataIsolation.closeTest();
        }
    }

    @Test
    void outsideATestTheRunOwnsEverythingItTagged() {
        assertThat(DataIsolation.tag("x")).isEqualTo("tf-run1-x");
        assertThat(DataIsolation.owns("tf-run1-t9-y")).isTrue();
        assertThat(DataIsolation.owns("tf-run2-t9-y")).isFalse();
        assertThat(DataIsolation.owns("tf-run10-y")).isFalse();
    }

    @Test
    void aSweeperRecognizesEveryRunButNotLookalikes() {
        assertThat(DataIsolation.isTestData("tf-oldrun-t3-anna@example.test")).isTrue();
        assertThat(DataIsolation.isTestData("customer tf-abc")).isTrue();
        assertThat(DataIsolation.isTestData("staff-anna")).isFalse();
        assertThat(DataIsolation.isTestData("anna")).isFalse();
    }

    @Test
    void configurationIsValidatedAndBlankRunKeepsTheGeneratedOne() {
        assertThatThrownBy(() -> DataIsolation.configure("tf", "run 1"))
                .hasMessageContaining("forge.data.isolation.run-tag must be letters and digits only");
        assertThatThrownBy(() -> DataIsolation.configure("t-f", null))
                .hasMessageContaining("forge.data.isolation.prefix");

        DataIsolation.configure("QA", "");
        String generated = DataIsolation.runTag();
        DataIsolation.configure("QA", null);

        assertThat(generated).matches("qa-[0-9a-f]{6}").isEqualTo(DataIsolation.runTag());
    }

    @Test
    void parallelThreadsHaveSeparateTestTags() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Callable<String> test = () -> {
                DataIsolation.openTest();
                try {
                    return DataIsolation.scopeTag();
                } finally {
                    DataIsolation.closeTest();
                }
            };
            List<String> tags = pool.invokeAll(List.of(test, test, test, test)).stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();

            assertThat(tags).doesNotHaveDuplicates();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void theWholeFixtureRunsWithoutLeakingTags() {
        EngineTestKit.engine("junit-jupiter").selectors(selectClass(DataIsolationTestkitFixture.class)).execute();

        assertThat(DataIsolation.testTag()).isEmpty();
    }
}
