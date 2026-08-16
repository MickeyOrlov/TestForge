package io.testforge.artifact;

import org.junit.jupiter.api.Test;

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
}
