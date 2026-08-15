package io.testforge.artifact;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestArtifactTest {

    @Test
    void shouldConstructWithAllValidFields() {
        Path path = Path.of("build/diagnostics/flow.json");
        Instant now = Instant.now();
        Map<String, String> meta = Map.of("env", "test");

        TestArtifact artifact = new TestArtifact(
                "module-flow",
                "flow-path",
                "execution-trace",
                path,
                "application/json",
                now,
                meta
        );

        assertThat(artifact.source()).isEqualTo("module-flow");
        assertThat(artifact.category()).isEqualTo("flow-path");
        assertThat(artifact.name()).isEqualTo("execution-trace");
        assertThat(artifact.file()).isEqualTo(path);
        assertThat(artifact.mediaType()).isEqualTo("application/json");
        assertThat(artifact.createdAt()).isEqualTo(now);
        assertThat(artifact.metadata()).containsEntry("env", "test");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankSource(String invalidSource) {
        Path path = Path.of("build/trace.log");
        assertThatThrownBy(() -> new TestArtifact(
                invalidSource,
                "category",
                "name",
                path,
                "text/plain",
                Instant.now(),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankCategory(String invalidCategory) {
        Path path = Path.of("build/trace.log");
        assertThatThrownBy(() -> new TestArtifact(
                "module-flow",
                invalidCategory,
                "name",
                path,
                "text/plain",
                Instant.now(),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void shouldRejectBlankName(String invalidName) {
        Path path = Path.of("build/trace.log");
        assertThatThrownBy(() -> new TestArtifact(
                "module-flow",
                "category",
                invalidName,
                path,
                "text/plain",
                Instant.now(),
                Map.of()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRejectNullFile() {
        assertThatThrownBy(() -> new TestArtifact(
                "module-flow",
                "category",
                "name",
                null,
                "text/plain",
                Instant.now(),
                Map.of()
        )).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void shouldDefaultMediaTypeWhenNullOrBlank(String blankMediaType) {
        Path path = Path.of("build/trace.log");
        TestArtifact artifact = new TestArtifact(
                "module-flow",
                "category",
                "name",
                path,
                blankMediaType,
                Instant.now(),
                Map.of()
        );

        assertThat(artifact.mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    void shouldDefaultCreatedAtWhenNull() {
        Path path = Path.of("build/trace.log");
        Instant before = Instant.now();

        TestArtifact artifact = new TestArtifact(
                "module-flow",
                "category",
                "name",
                path,
                "text/plain",
                null,
                Map.of()
        );

        assertThat(artifact.createdAt()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    void shouldDefaultMetadataWhenNullOrEmpty() {
        Path path = Path.of("build/trace.log");

        TestArtifact nullMeta = new TestArtifact(
                "module-flow", "category", "name", path, "text/plain", Instant.now(), null
        );
        TestArtifact emptyMeta = new TestArtifact(
                "module-flow", "category", "name", path, "text/plain", Instant.now(), Map.of()
        );

        assertThat(nullMeta.metadata()).isEmpty();
        assertThat(emptyMeta.metadata()).isEmpty();
    }

    @Test
    void shouldDefensivelyCopyMetadataAndMakeItUnmodifiable() {
        Path path = Path.of("build/trace.log");
        Map<String, String> mutableMeta = new HashMap<>();
        mutableMeta.put("key1", "val1");

        TestArtifact artifact = new TestArtifact(
                "module-flow",
                "category",
                "name",
                path,
                "text/plain",
                Instant.now(),
                mutableMeta
        );

        // Mutate original map after constructing TestArtifact
        mutableMeta.put("key2", "val2");

        assertThat(artifact.metadata())
                .hasSize(1)
                .containsEntry("key1", "val1")
                .doesNotContainKey("key2");

        // Attempting to modify returned metadata map must fail
        assertThatThrownBy(() -> artifact.metadata().put("key3", "val3"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
