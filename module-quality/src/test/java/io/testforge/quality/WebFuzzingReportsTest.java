package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** robustness from a Web Fuzzing Commons report, and other tools' reports that belong to an earlier build. */
class WebFuzzingReportsTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    // the layout EvoMaster 6.2.0 writes (WFC schemaVersion 0.7.0)
    private static final String REPORT = """
            {"schemaVersion": "0.7.0", "toolName": "EvoMaster", "toolVersion": "6.2.0",
             "faults": {"totalNumber": 3, "foundFaults": [
               {"operationId": "GET:/petclinic/api/v2/owners", "testCaseId": "EvoMaster_faults_Test.java#test_7",
                "faultCategories": [{"code": 100, "context": null}, {"code": 200, "context": "Type: x"}]},
               {"operationId": "GET:/petclinic/api/v2/owners", "testCaseId": "EvoMaster_faults_Test.java#test_9",
                "faultCategories": [{"code": 100, "context": null}]},
               {"operationId": "POST:/petclinic/api/owners", "testCaseId": "EvoMaster_faults_Test.java#test_1",
                "faultCategories": [{"code": 301, "context": null}, {"code": 999, "context": null}]}]},
             "problemDetails": {"rest": {"outputHttpCalls": 10}}, "totalTests": 3}""";

    private Path report(String json) throws IOException {
        Path file = dir.resolve("report.json");
        Files.writeString(file, json);
        return file;
    }

    private QualityDimension robustness(Path report, Instant notBefore) {
        return ModuleDimensions.robustness(null, mapper, List.of(report.toString()), notBefore);
    }

    @Test
    void faultsAreGroupedByOperationAndCategoryWithTheTestThatShowsThem() throws IOException {
        QualityDimension robustness = robustness(report(REPORT), null);

        assertThat(robustness.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(robustness.issues()).containsExactly("GET:/petclinic/api/v2/owners HTTP_STATUS_500",
                "GET:/petclinic/api/v2/owners SCHEMA_INVALID_RESPONSE", "POST:/petclinic/api/owners SECURITY_XSS",
                "POST:/petclinic/api/owners WFC_999");
        assertThat(robustness.score()).isEqualTo(60);
        assertThat(robustness.facts()).first().asString().startsWith("EvoMaster 6.2.0 (WFC 0.7.0): 4 fault groups "
                + "in 2 operations — 2 HTTP, 1 schema, 2 security findings");
        assertThat(robustness.facts()).contains(
                "fault: GET:/petclinic/api/v2/owners HTTP_STATUS_500 → EvoMaster_faults_Test.java#test_7");
    }

    @Test
    void aMissingStaleOrForeignReportIsNotMeasured() throws IOException {
        assertThat(robustness(dir.resolve("absent.json"), null).facts().getFirst()).startsWith("no WFC report at");
        assertThat(robustness(report("{\"faults\": 3}"), null).facts().getFirst())
                .endsWith("is not a WFC report (no schemaVersion / faults.foundFaults)");
        QualityDimension malformed = robustness(report("""
                {"schemaVersion": "0.7.0", "faults": {"totalNumber": 1, "foundFaults": [{"operationId": "GET:/owners"}]}}"""), null);
        assertThat(malformed.status()).as("a fault without categories is not a clean run")
                .isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(malformed.facts().getFirst()).endsWith("has a malformed fault (foundFaults[0] has no "
                + "faultCategories): its faults cannot be judged");
        assertThat(robustness(report("""
                {"schemaVersion": "0.7.0", "faults": {"foundFaults": [{"faultCategories": [{"code": 100}]}]}}"""), null)
                .facts().getFirst()).contains("foundFaults[0] has no operationId");
        assertThat(robustness(report("""
                {"schemaVersion": "0.7.0", "faults": {"foundFaults": [
                  {"operationId": "GET:/x", "faultCategories": [{"code": "500"}]}]}}"""), null)
                .facts().getFirst()).contains("a category without an integer code");

        Path old = report(REPORT);
        Files.setLastModifiedTime(old, FileTime.from(Instant.parse("2026-11-01T08:00:00Z")));
        QualityDimension stale = robustness(old, Instant.parse("2026-11-01T09:00:00Z"));
        assertThat(stale.status()).isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(stale.facts().getFirst()).contains("before this build").contains("a report of an earlier build");

        Path mutations = dir.resolve("pit/mutations.xml");
        Files.createDirectories(mutations.getParent());
        Files.writeString(mutations, "<mutations><mutation status='KILLED'/></mutations>");
        Files.setLastModifiedTime(mutations, FileTime.from(Instant.parse("2026-11-01T08:00:00Z")));
        assertThat(MutationDimension.of(new QualityProperties.MutationProperties(List.of(dir.resolve("pit").toString()),
                null), Instant.parse("2026-11-01T09:00:00Z")).status())
                .as("PIT from an earlier build").isEqualTo(QualityStatus.NOT_MEASURED);
    }
}
