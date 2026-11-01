package io.testforge.example;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.quality.*;
import io.testforge.security.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecurityQualityExampleTest {
  @Test
  void aRealSecurityReportWithMissingTlsEvidenceCannotPassQuality(@TempDir Path dir)
      throws Exception {
    Path run = dir.resolve("artifacts/r");
    var collector = new SecurityCollector(100);
    collector.covered("passive-response", "GET /orders", null, "measured", null);
    collector.covered(
        "tls-protocol", "api.example.invalid", null, "not_measured", "local limitation");
    var properties =
        new SecurityProperties(
            null, run.resolve("module-security").toString(), null, List.of(), Set.of(), null);
    var produced = new SecurityRunner(properties, collector, ArtifactSink.NO_OP, () -> true).run();
    assertThat(produced.measured()).isTrue();
    assertThat(produced.complete()).isFalse();
    Files.writeString(
        run.resolve("manifest.json"),
        """
        {"runId":"r","artifacts":[{"source":"module-security","category":"security",
        "name":"security-findings.json","path":"module-security/security-findings.json"}]}""");
    Path results = Files.createDirectories(dir.resolve("results"));
    Files.writeString(
        results.resolve("TEST-example.xml"),
        "<testsuite><testcase classname=\"Example\" name=\"passes\"/></testsuite>");
    var quality =
        new QualityRunner(
            new QualityProperties(
                true,
                List.of(results.toString()),
                dir.resolve("artifacts").toString(),
                "r",
                dir.resolve("quality").toString(),
                null,
                List.of("security"),
                dir.resolve("baseline.json").toString(),
                List.of(),
                null),
            new ObjectMapper(),
            ArtifactSink.NO_OP);
    var dimension = quality.run().dimension(QualityDimension.SECURITY).orElseThrow();
    assertThat(dimension.status()).isEqualTo(QualityStatus.NOT_MEASURED);
    assertThat(dimension.score()).isNull();
    assertThatThrownBy(quality::assertAcceptable)
        .isInstanceOf(QualityException.class)
        .hasMessageContaining("incomplete");
  }
}
