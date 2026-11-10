package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.quality.QualityProperties.SarifProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The static-analysis dimension, read from scanners' SARIF 2.1.0. */
class SarifDimensionTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    private Path fixture(String name) throws IOException {
        Path file = dir.resolve(name);
        try (InputStream in = getClass().getResourceAsStream("/sarif/" + name)) {
            Files.write(file, in.readAllBytes());
        }
        return file;
    }

    private Path report(String json) throws IOException {
        Path file = dir.resolve("scan.sarif");
        Files.writeString(file, json);
        return file;
    }

    private QualityDimension judge(Path report, Map<String, String> floors) {
        return SarifDimension.of(new SarifProperties(List.of(report.toString()), floors), mapper, null);
    }

    @Test
    void gitleaksWritesNoLevelSoItsSecretsAreWarningsUntilItsFloorSaysHigh() throws IOException {
        // gitleaks 8.30.1 on five planted fake leaks: no level, no security-severity, empty partialFingerprints
        Path gitleaks = fixture("gitleaks-8.30.1.sarif");

        QualityDimension asWritten = judge(gitleaks, null);
        assertThat(asWritten.status()).as("SARIF's default level is warning").isEqualTo(QualityStatus.WARN);
        assertThat(asWritten.issues()).hasSize(5).contains("gitleaks slack-webhook-url artifacts/notify.json",
                "gitleaks aws-access-token artifacts/config-dump.txt")
                .as("empty fingerprints are no fingerprint").allMatch(key -> !key.contains("#"));

        QualityDimension floored = judge(gitleaks, Map.of("GitLeaks", "high"));
        assertThat(floored.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(floored.score()).isZero();
        assertThat(floored.facts().getFirst()).startsWith("gitleaks: 5 results — 0 critical, 5 high, 0 medium, "
                + "0 low; 0 suppressed");
        assertThat(String.join("\n", floored.facts())).doesNotContain("REDACTED", "detected secret");
    }

    @Test
    void spectralOwaspFindingsAreOneIssuePerRuleAndOperationAndHoldWhenLinesMove() throws IOException {
        // tools/spectral on example-tests' demo-api.yaml: Spectral 6.17.0, OWASP API Top 10 ruleset 2.0.1,
        // with the document path as partial fingerprint
        Path spectral = fixture("spectral-6.17.0-owasp-2.0.1.sarif");

        QualityDimension lint = judge(spectral, null);
        assertThat(lint.status()).as("OWASP error-level rules are high").isEqualTo(QualityStatus.FAIL);
        assertThat(lint.facts().getFirst()).startsWith("spectral 6.17.0: 29 results — 0 critical, 10 high, "
                + "19 medium, 0 low; 0 suppressed");
        assertThat(lint.issues()).as("one per rule and operation, not one per rule and file").hasSize(23)
                .contains("spectral owasp:api2:2023-write-restricted example-tests/src/test/resources/openapi/"
                        + "demo-api.yaml #" + JunitResults.sha256("documentPath/v1=paths./api/v1/tasks.post\n")
                        .substring(0, 12));

        String moved = Files.readString(spectral).replaceAll("\"startLine\": (\\d+)", "\"startLine\": 1$1");
        assertThat(judge(report(moved), null).issues()).as("the keys hold when lines move")
                .containsExactlyInAnyOrderElementsOf(lint.issues());
    }

    // the shapes Trivy, Semgrep and CodeQL use: rule security-severity, levels, suppressions, fingerprints
    private static final String MIXED = """
            {"version": "2.1.0", "runs": [{
              "tool": {"driver": {"name": "Trivy", "version": "0.76.1", "rules": [
                {"id": "CVE-2026-0001", "properties": {"security-severity": "9.8"}},
                {"id": "CVE-2026-0002", "properties": {"security-severity": "5.3"}},
                {"id": "java.lang.security.weak-hash", "defaultConfiguration": {"level": "error"}}]}},
              "results": [
                {"ruleId": "CVE-2026-0001", "locations": [{"physicalLocation": {"artifactLocation": {"uri": "pom.xml"},
                  "region": {"startLine": 40}}}], "message": {"text": "jackson 2.0 is vulnerable, secret=xyz"}},
                {"ruleId": "CVE-2026-0002", "locations": [{"physicalLocation": {"artifactLocation": {"uri": "pom.xml"},
                  "region": {"startLine": 52}}}]},
                {"ruleId": "java.lang.security.weak-hash", "partialFingerprints": {"primaryLocationLineHash": "ab12:1"},
                  "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/main/java/Hash.java"},
                  "region": {"startLine": 9}}}]},
                {"ruleId": "java.lang.security.weak-hash", "partialFingerprints": {"primaryLocationLineHash": "ab12:1"},
                  "locations": [{"physicalLocation": {"artifactLocation": {"uri": "src/main/java/Hash.java"},
                  "region": {"startLine": 30}}}]},
                {"ruleId": "style.note", "level": "note", "locations": [{"physicalLocation": {
                  "artifactLocation": {"uri": "src/main/java/Hash.java"}}}]},
                {"ruleId": "info.only", "level": "none"},
                {"ruleId": "CVE-2026-0003", "level": "error", "suppressions": [{"kind": "external", "status": "accepted"}]},
                {"ruleId": "CVE-2026-0004", "level": "error", "suppressions": [{"kind": "external", "status": "rejected"}]},
                {"ruleId": "CVE-2026-0005", "level": "error", "baselineState": "absent"}]}]}
            """;

    @Test
    void severityComesFromSecuritySeverityThenLevelAndKeysHoldWhenLinesMove() throws IOException {
        QualityDimension mixed = judge(report(MIXED), null);

        assertThat(mixed.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(mixed.issues()).containsExactlyInAnyOrder(
                "Trivy CVE-2026-0001 pom.xml",
                "Trivy CVE-2026-0002 pom.xml",
                "Trivy java.lang.security.weak-hash src/main/java/Hash.java #"
                        + JunitResults.sha256("primaryLocationLineHash=ab12:1\n").substring(0, 12),
                "Trivy style.note src/main/java/Hash.java",
                "Trivy CVE-2026-0004 (no location)");
        // 1 critical (9.8), 2 high (weak-hash error, rejected suppression), 1 medium (5.3), 1 low (note)
        assertThat(mixed.score()).isEqualTo(100 - 40 - 2 * 20 - 5 - 1);
        assertThat(mixed.facts().getFirst()).contains("1 suppressed");
        assertThat(mixed.facts()).contains("critical: Trivy CVE-2026-0001 pom.xml (pom.xml:40)");
        assertThat(String.join("\n", mixed.facts())).as("messages may hold secrets").doesNotContain("secret=xyz");
    }

    @Test
    void aMissingStaleOrMalformedReportIsNotMeasured() throws IOException {
        assertThat(SarifDimension.of(new SarifProperties(null, null), mapper, null).facts().getFirst())
                .startsWith("no SARIF report configured");
        assertThat(judge(dir.resolve("absent.sarif"), null).facts().getFirst()).startsWith("no SARIF report at");
        assertThat(judge(report("{\"version\": \"2.0.0\", \"runs\": []}"), null).facts().getFirst())
                .endsWith("is not a SARIF 2.1 report (no version 2.1.x / runs)");
        assertThat(judge(report("""
                {"version": "2.1.0", "runs": [{"tool": {"driver": {"name": "x"}}, "results": [{"level": "error"}]}]}"""),
                null).facts().getFirst()).endsWith("has a malformed result (x results[0] has no ruleId)");
        assertThat(judge(report("""
                {"version": "2.1.0", "runs": [{"tool": {"driver": {}}, "results": []}]}"""), null).facts().getFirst())
                .endsWith("has a run without tool.driver.name");

        Path old = report(MIXED);
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-11-01T08:00:00Z")));
        QualityDimension stale = SarifDimension.of(new SarifProperties(List.of(old.toString()), null), mapper,
                Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(stale.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(stale.facts().getFirst()).contains("a report of an earlier build");
    }

    @Test
    void theBaselineAcceptedListOwnersAndTheGateWorkOnScannerFindings() throws IOException {
        Path sarif = fixture("gitleaks-8.30.1.sarif");
        Files.createDirectories(dir.resolve(".github"));
        Files.writeString(dir.resolve(".github/CODEOWNERS"), "artifacts/  @org/platform\n");
        Path results = dir.resolve("results/TEST-s.xml");
        Files.createDirectories(results.getParent());
        Files.writeString(results, "<testsuite name=\"s\"><testcase classname=\"io.acme.T\" name=\"ok\"/></testsuite>");
        Path config = dir.resolve("quality.yml");
        Files.writeString(config, """
                forge:
                  quality:
                    junit-results: [%s]
                    artifacts-root: %s
                    output-dir: %s
                    baseline-file: %s
                    fail-on: [functional, static-analysis]
                    codeowners: %s
                    sarif:
                      reports: [%s]
                      tool-severity: {gitleaks: high}
                """.formatted(dir.resolve("results"), dir.resolve("artifacts-index"), dir.resolve("out"),
                dir.resolve("baseline.json"), dir.resolve(".github/CODEOWNERS"), sarif));
        java.io.PrintStream quiet = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());

        assertThat(QualityCli.run(new String[] {"check", "--config", config.toString()}, quiet, quiet))
                .as("five new high findings").isEqualTo(QualityCli.FAILED);
        String markdown = Files.readString(dir.resolve("out/quality.md"));
        assertThat(markdown).contains("| static-analysis | FAIL | enforced |").contains("@org/platform");

        assertThat(QualityCli.run(new String[] {"check", "--config", config.toString(), "--set",
                "accepted[0]=static-analysis gitleaks jwt artifacts/session.txt  # SEC-1, until 2026-12-01"},
                quiet, quiet)).as("one accepted, four still new").isEqualTo(QualityCli.FAILED);

        assertThat(QualityCli.run(new String[] {"baseline", "--config", config.toString()}, quiet, quiet))
                .isEqualTo(QualityCli.PASS);
        assertThat(QualityCli.run(new String[] {"check", "--config", config.toString()}, quiet, quiet))
                .as("known since the baseline").isEqualTo(QualityCli.PASS);
    }
}
