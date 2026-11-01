package io.testforge.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ArtifactSinkTest {

    @Test
    void noOpDirectoryForIsSafeAndNeverThrows() {
        assertThatNoException().isThrownBy(() -> {
            Path path1 = ArtifactSink.NO_OP.directoryFor("module-flow");
            assertThat(path1).isNotNull();

            Path path2 = ArtifactSink.NO_OP.directoryFor(null);
            assertThat(path2).isNotNull();
        });
    }

    @Test
    void noOpRegisterIsSafeAndNeverThrows() {
        TestArtifact artifact = new TestArtifact(
                "module-flow",
                "category",
                "name",
                Path.of("build/test.txt"),
                "text/plain",
                null,
                null
        );

        assertThatNoException().isThrownBy(() -> {
            ArtifactSink.NO_OP.register(artifact);
            ArtifactSink.NO_OP.register(null);
        });
    }

    @Test
    void noOpWriteReturnsUsableTestArtifact() {
        TestArtifact result = ArtifactSink.NO_OP.write(
                "module-flow",
                "flow-path",
                "trace.json",
                "application/json",
                "{\"status\":\"OK\"}"
        );

        assertThat(result).isNotNull();
        assertThat(result.source()).isEqualTo("module-flow");
        assertThat(result.category()).isEqualTo("flow-path");
        assertThat(result.name()).isEqualTo("trace.json");
        assertThat(result.mediaType()).isEqualTo("application/json");
        assertThat(result.file()).isNotNull();
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.metadata()).isEmpty();
    }

    @Test
    void noOpWriteNeverThrowsEvenWithNullOrBlankInputs() {
        assertThatNoException().isThrownBy(() -> {
            TestArtifact result = ArtifactSink.NO_OP.write(null, null, null, null, null);
            assertThat(result).isNotNull();
            assertThat(result.file()).isNotNull();
            assertThat(result.createdAt()).isNotNull();
        });
    }

    @Test
    void newFileNeverHandsOutAPathAnotherContextAlreadyHas(@org.junit.jupiter.api.io.TempDir Path run) {
        ArtifactSink sink = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                return run.resolve(source);
            }

            @Override
            public void register(TestArtifact artifact) {
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return null;
            }
        };

        Path first = sink.newFile("module-http", "http-summary.json");
        Path second = sink.newFile("module-http", "http-summary.json");
        Path third = sink.newFile("module-http", "http-summary.json");
        Path bare = sink.newFile("module-http", "README");

        assertThat(first).isEqualTo(run.resolve("module-http/http-summary.json")).exists();
        assertThat(second.getFileName()).hasToString("http-summary-2.json");
        assertThat(third.getFileName()).hasToString("http-summary-3.json");
        assertThat(bare.getFileName()).hasToString("README");
        assertThat(Files.isRegularFile(second)).isTrue();
    }

    @Test
    void newFileNeverThrowsWhenTheDirectoryCannotBeHad() {
        ArtifactSink broken = new ArtifactSink() {
            @Override
            public Path directoryFor(String source) {
                throw new IllegalStateException("no run");
            }

            @Override
            public void register(TestArtifact artifact) {
            }

            @Override
            public TestArtifact write(String source, String category, String name, String mediaType, String content) {
                return null;
            }
        };

        assertThatNoException().isThrownBy(() -> broken.newFile("x", "a.json"));
    }
}
