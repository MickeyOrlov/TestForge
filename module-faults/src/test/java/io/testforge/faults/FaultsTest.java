package io.testforge.faults;

import static org.assertj.core.api.Assertions.*;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FaultsTest {
    private FaultsProperties properties(boolean enabled, List<String> allowed) {
        return new FaultsProperties(enabled, null, null, null, false, 0, 0,
                Map.of("db", "db:5432"), allowed);
    }
    static class Fake implements FaultBackend {
        final Set<String> active = new HashSet<>();
        final List<String> operations = new ArrayList<>();
        boolean failDelete;
        int count;
        public Handle create(String id, String upstream) {
            active.add(id); int port = 10000 + count++;
            return new Handle() {
                public Endpoint endpoint() { return new Endpoint("localhost", port); }
                public void apply(String kind, long value) { operations.add(kind); }
                public void delete() throws IOException {
                    if (failDelete) throw new IOException("secret-upstream");
                    active.remove(id);
                }
            };
        }
        public void close() { }
    }
    @Test void contextsAreInertAndDefaultsAreDisabled() {
        var context = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeFaultsAutoConfiguration.class));
        context.run(c -> assertThat(c).doesNotHaveBean(Faults.class));
        context.withPropertyValues("forge.faults.enabled=true").run(c -> assertThat(c).hasSingleBean(Faults.class));
    }
    @Test void scopesAreIsolatedAndFailureStillCleansUp() {
        Fake fake = new Fake();
        try (var faults = new Faults(properties(true, List.of("db:5432")), fake, ArtifactSink.NO_OP)) {
            try (var a = faults.on("db"); var b = faults.on("db")) {
                assertThat(a.endpoint()).isNotEqualTo(b.endpoint());
                a.latency(Duration.ofSeconds(2)).jitter(Duration.ofMillis(10)).timeout(Duration.ofSeconds(1))
                        .resetPeer().bandwidth(64).down();
                assertThat(fake.active).hasSize(2);
                assertThat(fake.operations).containsExactly("latency", "jitter", "timeout", "reset_peer", "bandwidth", "down");
            }
            assertThat(fake.active).isEmpty();
            assertThatThrownBy(() -> { try (var a = faults.on("db")) { a.down(); throw new IllegalArgumentException("test failed"); } })
                    .hasMessage("test failed");
            assertThat(fake.active).isEmpty();
        }
    }
    @Test void unknownUnallowlistedAndDisabledNeverCallTheBackend() {
        Fake fake = new Fake();
        for (var props : List.of(properties(false, List.of("db:5432")), properties(true, List.of()))) {
            try (var faults = new Faults(props, fake, null)) { assertThatThrownBy(() -> faults.on("db")).isInstanceOf(RuntimeException.class); }
        }
        assertThat(fake.count).isZero();
        assertThatThrownBy(() -> new FaultsProperties(true, "http://localhost:8474/?token=secret", null, null,
                true, 0, 0, Map.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failedDeletionCanBeRetriedAndNeverLeaksTheUpstreamInErrors() {
        Fake fake = new Fake();
        var faults = new Faults(properties(true, List.of("db:5432")), fake, null);
        var scope = faults.on("db"); fake.failDelete = true;
        assertThatThrownBy(scope::close).hasMessageContaining("cleanup failed").hasMessageNotContaining("secret");
        assertThat(fake.active).hasSize(1);
        fake.failDelete = false; scope.close(); scope.close(); faults.close();
        assertThat(fake.active).isEmpty();
        assertThatThrownBy(scope::down).hasMessageContaining("closed");
    }

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;
    @Test void evidenceNamesOnlyLogicalProxiesAndRecordsRestorationAndCleanupFailures() throws Exception {
        ArtifactSink sink = new ArtifactSink() {
            public java.nio.file.Path directoryFor(String source) { return dir; }
            public void register(io.testforge.artifact.TestArtifact artifact) { }
            public io.testforge.artifact.TestArtifact write(String s, String c, String n, String m, String text) {
                throw new AssertionError("Producer allocates its own collision-safe path");
            }
        };
        Fake fake = new Fake();
        try (var faults = new Faults(properties(true, List.of("db:5432")), fake, sink)) {
            var scope = faults.on("db"); scope.latency(Duration.ofMillis(10)).restore();
            fake.failDelete = true;
            assertThatThrownBy(scope::close).hasMessageNotContaining("secret");
            String failed = java.nio.file.Files.readString(dir.resolve("faults.json"));
            assertThat(failed).contains("durationMillis", "restore", "cleanupSucceeded\" : false")
                    .doesNotContain("db:5432", "secret-upstream");
            fake.failDelete = false; scope.close();
            assertThat(java.nio.file.Files.readString(dir.resolve("faults-2.json")))
                    .contains("cleanupSucceeded\" : true");
        }
    }
    @Test void externalModeRequiresCleanupAcknowledgementBeforeAnyTraffic() {
        var props = new FaultsProperties(true, "http://localhost:8474", null, null, false, 0, 0,
                Map.of("db", "db:5432"), List.of("db:5432"));
        Fake fake = new Fake();
        try (var faults = new Faults(props, fake, null)) {
            assertThatThrownBy(() -> faults.on("db")).hasMessageContaining("acknowledged");
        }
        assertThat(fake.count).isZero();
    }

    @Test void controllerClosesUnclosedScopesAndRetriesAfterFailureWithoutAdmittingNewScopes() {
        Fake fake = new Fake();
        var faults = new Faults(properties(true, List.of("db:5432")), fake, null);
        faults.on("db").down(); faults.on("db").latency(Duration.ofMillis(10));
        fake.failDelete = true;
        assertThatThrownBy(faults::close).hasMessageContaining("cleanup failed");
        assertThatThrownBy(() -> faults.on("db")).hasMessageContaining("closed");
        fake.failDelete = false; faults.close(); faults.close();
        assertThat(fake.active).isEmpty();
    }
}
