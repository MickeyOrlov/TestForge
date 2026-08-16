package io.testforge.reporting;

import static org.assertj.core.api.Assertions.assertThatNoException;

import io.testforge.artifact.TestArtifact;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AllureArtifactAttachments}.
 *
 * <p>Note: {@code allure-java-commons} is a {@code compileOnly} dependency in
 * {@code module-reporting} and is not present on the test runtime classpath by default.
 * These tests exercise the adapter's failure handling and best-effort guarantee,
 * verifying that missing files, unreadable files, null inputs, or an absent Allure
 * runtime dependency (e.g. {@link NoClassDefFoundError}) are safely caught and never
 * throw an exception to fail a test.
 */
class AllureArtifactAttachmentsTest {

    @TempDir
    Path tempDir;

    @Test
    void attachNullArtifactDoesNotThrow() {
        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach((TestArtifact) null));
    }

    @Test
    void attachMissingFileArtifactDoesNotThrow() {
        TestArtifact artifact = new TestArtifact(
                "test-module",
                "test-category",
                "missing-file",
                tempDir.resolve("non-existent-file.json"),
                "application/json",
                Instant.now(),
                Map.of()
        );

        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach(artifact));
    }

    @Test
    void attachExistingFileArtifactHandlesAbsentAllureGracefully() throws IOException {
        Path file = tempDir.resolve("sample.json");
        Files.writeString(file, "{\"key\":\"value\"}");

        TestArtifact artifact = new TestArtifact(
                "test-module",
                "test-category",
                "sample-json",
                file,
                "application/json",
                Instant.now(),
                Map.of()
        );

        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach(artifact));
    }

    @Test
    void attachExistingFileWithExtensionAndMediaTypeHandlesAbsentAllureGracefully() throws IOException {
        Path file = tempDir.resolve("sample.txt");
        Files.writeString(file, "hello world");

        TestArtifact artifact = new TestArtifact(
                "test-module",
                "test-category",
                "sample-txt",
                file,
                "text/plain",
                Instant.now(),
                Map.of()
        );

        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach(artifact));
    }

    @Test
    void attachNullCollectionDoesNotThrow() {
        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach((Collection<TestArtifact>) null));
        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attachAll(null));
    }

    @Test
    void attachCollectionOfArtifactsDoesNotThrow() throws IOException {
        Path file = tempDir.resolve("artifact1.log");
        Files.writeString(file, "log content");

        TestArtifact validArtifact = new TestArtifact(
                "test-module",
                "test-category",
                "valid-artifact",
                file,
                "text/plain",
                Instant.now(),
                Map.of()
        );

        TestArtifact missingArtifact = new TestArtifact(
                "test-module",
                "test-category",
                "missing-artifact",
                tempDir.resolve("does-not-exist.bin"),
                "application/octet-stream",
                Instant.now(),
                Map.of()
        );

        List<TestArtifact> list = List.of(validArtifact, missingArtifact);

        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attach(list));
        assertThatNoException().isThrownBy(() -> AllureArtifactAttachments.attachAll(list));
    }
}
