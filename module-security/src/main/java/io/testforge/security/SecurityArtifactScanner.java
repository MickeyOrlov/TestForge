package io.testforge.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Bounded pre-upload scan. Symlinks are never followed and read failures cannot become a clean
 * result.
 */
public class SecurityArtifactScanner {
  private static final Pattern PATTERNS =
      Pattern.compile(
          "(?s)(-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE"
              + " KEY-----|eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]*|(?i:(?:password|api[_-]?key|access[_-]?token|client[_-]?secret)\\s*[\"'=:\\s]+[A-Za-z0-9_+/=-]{8,}))");
  private final SecurityArtifactProperties properties;
  private final SecuritySecretRegistry secrets;
  private final SecurityCollector collector;

  public SecurityArtifactScanner(
      SecurityArtifactProperties p, SecuritySecretRegistry secrets, SecurityCollector collector) {
    this.properties = p;
    this.secrets = secrets;
    this.collector = collector;
  }

  public void scan() {
    if (!properties.enabled())
      throw new IllegalStateException("forge.security.artifacts-scan.enabled must be true");
    long[] bytes = {0};
    int[] files = {0};
    for (int index = 0; index < properties.roots().size(); index++) {
      Path root = Path.of(properties.roots().get(index));
      String label = "artifact-root-" + index;
      if (Files.isSymbolicLink(root) || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
        incomplete(label, "artifact root is absent or symbolic");
        continue;
      }
      try {
        Files.walkFileTree(
            root,
            new SimpleFileVisitor<>() {
              @Override
              public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String target = label + " " + artifactPath(root.relativize(file));
                if (++files[0] > properties.maxFiles() || bytes[0] >= properties.maxTotalBytes()) {
                  incomplete(label, "scan budget exhausted");
                  return FileVisitResult.TERMINATE;
                }
                if (attributes.isSymbolicLink() || !attributes.isRegularFile()) {
                  incomplete(target, "symbolic or non-regular file not scanned");
                  return FileVisitResult.CONTINUE;
                }
                try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                  long remaining = properties.maxTotalBytes() - bytes[0];
                  int limit = (int) Math.min(properties.maxFileBytes(), remaining);
                  byte[] content = stream.readNBytes(limit + 1);
                  bytes[0] += content.length;
                  String text =
                      new String(
                          content, 0, Math.min(limit, content.length), StandardCharsets.UTF_8);
                  if (content.length > limit)
                    incomplete(target, "artifact exceeds scan budget; only prefix inspected");
                  else collector.covered("artifact-secret", target, null, "measured", null);
                  if (secrets.matches(text) || PATTERNS.matcher(text).find())
                    collector.finding(
                        "artifact-secret",
                        Severity.CRITICAL,
                        target,
                        "artifact contains a known secret or credential/private-key pattern",
                        "API8:2023");
                } catch (IOException e) {
                  incomplete(target, "artifact cannot be read safely");
                }
                return FileVisitResult.CONTINUE;
              }

              @Override
              public FileVisitResult visitFileFailed(Path file, IOException e) {
                incomplete(label, "artifact traversal or read failed");
                return FileVisitResult.CONTINUE;
              }
            });
      } catch (IOException e) {
        incomplete(label, "artifact traversal failed");
      }
    }
    if (files[0] == 0) incomplete("artifacts", "no files were scanned");
    if (!secrets.complete()) incomplete("artifacts", "known-secret registry reached its limit");
  }

  public void assertClean() {
    scan();
    if (collector.findings().stream().anyMatch(f -> f.check().equals("artifact-secret"))
        || collector.coverage().stream()
            .anyMatch(
                c -> c.check().equals("artifact-secret") && c.status().equals("not_measured")))
      throw new AssertionError(
          "artifact secret scan found a leak or incomplete coverage; do not upload artifacts");
  }

  private String artifactPath(Path relative) {
    StringBuilder safe = new StringBuilder();
    for (Path component : relative) {
      String name = component.toString();
      safe.append('/')
          .append(
              !secrets.matches(name) && name.matches("[A-Za-z][A-Za-z_-]{0,39}(\\.[A-Za-z]{1,8})?")
                  ? name
                  : "{value}");
    }
    return safe.isEmpty() ? "/" : safe.toString();
  }

  private void incomplete(String target, String reason) {
    collector.covered("artifact-secret", target, null, "not_measured", reason);
  }
}
