package io.testforge.security;

import static org.assertj.core.api.Assertions.*;

import io.testforge.artifact.ArtifactSink;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityArtifactTest {
  SecurityArtifactProperties properties(Path root, Long file, Long total, Integer count) {
    return new SecurityArtifactProperties(true, List.of(root.toString()), file, total, count);
  }

  @Test
  void archivesAreOpenedAndMediaIsNotText(@TempDir Path dir) throws Exception {
    String secret = "synthetic-trace-credential";
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve("trace.zip")))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("trace.network"));
      zip.write(("Authorization: Bearer " + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(dir.resolve("bundle.zip")))) {
      zip.putNextEntry(new java.util.zip.ZipEntry("inner.zip"));
      zip.write(new byte[] {1, 2, 3});
      zip.closeEntry();
    }
    Files.write(dir.resolve("failure.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G'});
    var secrets = new SecuritySecretRegistry();
    secrets.register(secret);
    var collector = new SecurityCollector(100);

    new SecurityArtifactScanner(properties(dir, null, null, null), secrets, collector).scan();

    assertThat(collector.findings())
        .singleElement()
        .satisfies(f -> assertThat(f.target()).endsWith("/trace.zip"));
    assertThat(collector.coverage())
        .extracting(c -> c.target().replaceAll("^artifact-root-0 ", "") + " " + c.status())
        .contains("/trace.zip measured", "/bundle.zip not_measured", "/failure.png not_applicable");
    assertThat(collector.findings().toString()).doesNotContain(secret);
  }

  @Test
  void plantedSecretsAndPrivateKeysFailBeforeUploadWithoutEchoingTheValue(@TempDir Path dir)
      throws Exception {
    String secret = "synthetic-planted-credential";
    Files.writeString(dir.resolve("http.log"), "unmasked=" + secret);
    Files.writeString(
        dir.resolve("diagnostic.txt"), "-----BEGIN PRIVATE KEY-----\nsynthetic-content");
    var secrets = new SecuritySecretRegistry();
    secrets.register(secret);
    var collector = new SecurityCollector(100);
    var scanner =
        new SecurityArtifactScanner(properties(dir, null, null, null), secrets, collector);
    assertThatThrownBy(scanner::assertClean)
        .isInstanceOf(AssertionError.class)
        .hasMessageNotContaining(secret);
    assertThat(collector.findings()).hasSize(2).allMatch(f -> f.severity() == Severity.CRITICAL);
    assertThat(collector.findings().toString()).doesNotContain(secret, "synthetic-content");
    var reportProps =
        new SecurityProperties(
            null,
            dir.resolve("reports").toString(),
            null,
            List.of(collector.findings().get(0).id() + " # synthetic accepted fixture"),
            Set.of(),
            null);
    var report = new SecurityRunner(reportProps, collector, ArtifactSink.NO_OP, () -> true);
    assertThatThrownBy(report::assertAcceptable)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("blocks upload");
    for (String name : List.of("security-findings.json", "security.md", "security.sarif"))
      assertThat(Files.readString(dir.resolve("reports").resolve(name))).doesNotContain(secret);
    assertThat(secrets.toString()).isEqualTo("SecuritySecretRegistry[redacted]");
  }

  @Test
  void cleanFilesPassButOversizeAndMissingRootsAreNotMeasured(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("safe.txt"), "masked=***");
    new SecurityArtifactScanner(
            properties(dir, null, null, null),
            new SecuritySecretRegistry(),
            new SecurityCollector(100))
        .assertClean();
    var limited = new SecurityCollector(100);
    assertThatThrownBy(
            () ->
                new SecurityArtifactScanner(
                        properties(dir, 4L, null, null), new SecuritySecretRegistry(), limited)
                    .assertClean())
        .isInstanceOf(AssertionError.class);
    assertThat(limited.coverage()).anyMatch(c -> c.status().equals("not_measured"));
    assertThatThrownBy(
            () ->
                new SecurityArtifactScanner(
                        properties(dir.resolve("missing"), null, null, null),
                        new SecuritySecretRegistry(),
                        new SecurityCollector(100))
                    .assertClean())
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void symbolicLinksAreNotFollowedAndTotalBudgetIsEnforced(@TempDir Path dir, @TempDir Path outside)
      throws Exception {
    String secret = "synthetic-outside-secret";
    Files.writeString(outside.resolve("secret.txt"), secret);
    Files.createSymbolicLink(dir.resolve("link"), outside.resolve("secret.txt"));
    var registry = new SecuritySecretRegistry();
    registry.register(secret);
    var c = new SecurityCollector(100);
    assertThatThrownBy(
            () ->
                new SecurityArtifactScanner(properties(dir, null, null, null), registry, c)
                    .assertClean())
        .isInstanceOf(AssertionError.class);
    assertThat(c.findings()).isEmpty();
    assertThat(c.coverage())
        .anyMatch(row -> row.reason() != null && row.reason().contains("symbolic"));
    Files.delete(dir.resolve("link"));
    Files.writeString(dir.resolve("one.txt"), "12345678");
    Files.writeString(dir.resolve("two.txt"), "12345678");
    assertThatThrownBy(
            () ->
                new SecurityArtifactScanner(
                        properties(dir, null, 8L, null), registry, new SecurityCollector(100))
                    .assertClean())
        .isInstanceOf(AssertionError.class);
  }
}
