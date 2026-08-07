package io.testforge.api.explorer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The gate between a documented operation and somebody's environment.
 */
class SafetyPolicyTest {

    @Test
    void safeMethodsNeedNoOptIn() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of()));

        assertThat(policy.refuse(ExplorerFixtures.operation("listTasks"))).isEmpty();
        assertThat(policy.refuse(ExplorerFixtures.operation("getTask"))).isEmpty();
    }

    @Test
    void writeMethodsAreRefusedByDefault() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of()));

        assertThat(policy.refuse(ExplorerFixtures.operation("createTask")))
                .contains(SkipReason.METHOD_NOT_ENABLED);
        assertThat(policy.refuse(ExplorerFixtures.operation("deleteTask")))
                .contains(SkipReason.METHOD_NOT_ENABLED);
    }

    @Test
    void listingAnUnsafeMethodIsNotEnoughOnItsOwn() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of(
                "methods", Set.of("GET", "DELETE"))));

        assertThat(policy.refuse(ExplorerFixtures.operation("deleteTask")))
                .contains(SkipReason.UNSAFE_METHOD_NOT_ALLOWED);
    }

    @Test
    void unsafeMethodsPassOnlyWithBothKeys() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of(
                "methods", Set.of("GET", "DELETE"),
                "allowUnsafeMethods", true)));

        assertThat(policy.refuse(ExplorerFixtures.operation("deleteTask"))).isEmpty();
    }

    @Test
    void excludedPathsAreRefused() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of(
                "excludePaths", List.of("/tasks/*"))));

        assertThat(policy.refuse(ExplorerFixtures.operation("getTask")))
                .contains(SkipReason.PATH_EXCLUDED);
        assertThat(policy.refuse(ExplorerFixtures.operation("listTasks"))).isEmpty();
    }

    @Test
    void doubleWildcardAlsoMatchesTheCollectionItself() {
        // standard Ant semantics, and a trap worth pinning down: /tasks/**
        // excludes /tasks as well, not only its sub-resources
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of(
                "excludePaths", List.of("/tasks/**"))));

        assertThat(policy.refuse(ExplorerFixtures.operation("listTasks")))
                .contains(SkipReason.PATH_EXCLUDED);
    }

    @Test
    void narrowingTheIncludeListSkipsEverythingElse() {
        SafetyPolicy policy = new SafetyPolicy(ExplorerFixtures.properties(Map.of(
                "includePaths", List.of("/reports"))));

        assertThat(policy.refuse(ExplorerFixtures.operation("listTasks")))
                .contains(SkipReason.PATH_NOT_INCLUDED);
        assertThat(policy.refuse(ExplorerFixtures.operation("listReports"))).isEmpty();
    }
}
