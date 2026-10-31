package io.testforge.data.cleanup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LeftoverReporterTest {

    @TempDir
    Path runRoot;

    private final List<TestArtifact> registered = new CopyOnWriteArrayList<>();

    private final ArtifactSink sink = new ArtifactSink() {
        @Override
        public Path directoryFor(String source) {
            return runRoot.resolve(source);
        }

        @Override
        public void register(TestArtifact artifact) {
            registered.add(artifact);
        }

        @Override
        public TestArtifact write(String source, String category, String name, String mediaType, String content) {
            throw new UnsupportedOperationException();
        }
    };

    @BeforeEach
    void drain() {
        Leftovers.drain();
    }

    @Test
    void publishesWhatCouldNotBeUndoneAsAnUnhealthyReport() throws Exception {
        Leftovers.record("OrdersTest > cancels()", new Cleanup.Failure("order o-1",
                new IllegalStateException("409 order is locked\nat line two")), "tf-run1-t4", false);
        LeftoverReporter reporter = new LeftoverReporter(sink);
        reporter.start();

        reporter.stop();

        assertThat(registered).singleElement().satisfies(artifact -> {
            assertThat(artifact.source()).isEqualTo("module-data");
            assertThat(artifact.name()).isEqualTo("leftovers.json");
            assertThat(artifact.metadata()).containsEntry("healthy", "false").containsEntry("leftovers", "1");
        });
        JsonNode report = new ObjectMapper().readTree(runRoot.resolve("module-data/leftovers.json").toFile());
        assertThat(report.get("count").asInt()).isEqualTo(1);
        assertThat(report.get("runTag").asText()).startsWith("tf-");
        JsonNode leftover = report.get("leftovers").get(0);
        assertThat(leftover.get("test").asText()).isEqualTo("OrdersTest > cancels()");
        assertThat(leftover.get("description").asText()).isEqualTo("order o-1");
        assertThat(leftover.get("error").asText()).isEqualTo("java.lang.IllegalStateException: 409 order is locked");
        assertThat(leftover.get("scopeTag").asText()).isEqualTo("tf-run1-t4");
        assertThat(leftover.get("testFailed").asBoolean()).isFalse();
        assertThat(leftover.get("at").asText()).isNotBlank();
        assertThat(reporter.isRunning()).isFalse();
    }

    @Test
    void aCleanRunWritesNothing() {
        LeftoverReporter reporter = new LeftoverReporter(sink);
        reporter.start();
        reporter.stop();

        assertThat(registered).isEmpty();
    }

    @Test
    void eachLeftoverIsPublishedOnceAcrossContexts() {
        Leftovers.record("A > a()", new Cleanup.Failure("x", new RuntimeException("boom")), "tf-r", true);
        new LeftoverReporter(sink).stop();
        new LeftoverReporter(sink).stop();

        assertThat(registered).hasSize(1);
        assertThat(Leftovers.recorded()).isEmpty();
    }

    @Test
    void aBrokenSinkNeverFailsTheContextStop() {
        Leftovers.record("A > a()", new Cleanup.Failure("x", new RuntimeException("boom")), "tf-r", false);
        ArtifactSink broken = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new IllegalStateException("disk gone");
            }

            @Override
            public void register(TestArtifact artifact) {
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType,
                                      String content) {
                return null;
            }
        };

        assertThatCode(() -> new LeftoverReporter(broken).stop()).doesNotThrowAnyException();
        assertThatCode(() -> new LeftoverReporter(null).stop()).doesNotThrowAnyException();
    }

    @Test
    void longErrorsAreCut() {
        Leftovers.record("A > a()", new Cleanup.Failure("x", new RuntimeException("e".repeat(2000))), "tf-r", false);

        assertThat(Leftovers.recorded().getFirst().error()).hasSizeLessThan(520).endsWith("…");
        Leftovers.drain();
    }
}
