package io.testforge.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArtifactSinkRegisterFileTest {

    private final List<TestArtifact> registered = new ArrayList<>();

    private final ArtifactSink recording = new ArtifactSink() {
        @Override
        public Path directoryFor(String source) {
            return Path.of("unused");
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

    @Test
    void aRelativeFileIsRecordedWithItsConfiguredLocation() {
        recording.registerFile("module-x", "report", "report.json", Path.of("build", "x", "report.json"),
                "application/json", Map.of("healthy", "true"));

        assertThat(registered).singleElement().satisfies(artifact -> {
            assertThat(artifact.name()).isEqualTo("report.json");
            assertThat(artifact.metadata())
                    .containsEntry("healthy", "true")
                    .containsEntry("location", "build/x/report.json");
        });
    }

    @Test
    void anAbsoluteFileNeverLeaksItsPath() {
        recording.registerFile("module-x", "report", "report.json", Path.of("/home/someone/build/report.json")
                .toAbsolutePath(), "application/json", null);

        assertThat(registered.getFirst().metadata()).doesNotContainKey("location");
    }

    @Test
    void invalidArgumentsAreSwallowed() {
        assertThatCode(() -> recording.registerFile(" ", "report", "r", Path.of("r"), null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> recording.registerFile("module-x", "report", "r", null, null, null))
                .doesNotThrowAnyException();
        assertThat(registered).isEmpty();
    }

    @Test
    void aFailingSinkNeverFailsTheProducer() {
        ArtifactSink broken = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return Path.of("unused");
            }

            @Override
            public void register(TestArtifact artifact) {
                throw new IllegalStateException("disk full");
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                throw new IllegalStateException("disk full");
            }
        };

        assertThatCode(() -> broken.registerFile("module-x", "report", "r", Path.of("r"), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void theNoOpSinkAcceptsRegistrations() {
        assertThatCode(() -> ArtifactSink.NO_OP.registerFile("module-x", "report", "r", Path.of("r"), null, Map.of()))
                .doesNotThrowAnyException();
    }
}
