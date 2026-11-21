package io.testforge.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.quality.QualityProperties.DiffCoverageProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The diff-coverage dimension, read from diff-cover's --json-report. */
class DiffCoverageDimensionTest {

    @TempDir
    Path dir;

    private final ObjectMapper mapper = new ObjectMapper();

    private Path report(String json) throws IOException {
        Path file = dir.resolve("diff-coverage.json");
        Files.writeString(file, json);
        return file;
    }

    private QualityDimension judge(Path report, Double minScore, Integer minLines) {
        return DiffCoverageDimension.of(new DiffCoverageProperties(report.toString(), minScore, minLines), mapper);
    }

    // the layout of diff_cover's JsonReportGenerator.report_dict
    private static final String REPORT = """
            {"report_name": "build/reports/jacoco/test/jacocoTestReport.xml",
             "diff_name": "origin/main...HEAD, staged and unstaged changes",
             "src_stats": {
               "src/main/java/io/acme/Prices.java": {"percent_covered": 50.0, "violation_lines": [14, 12, 13, 20],
                 "covered_lines": [10, 11, 15, 16], "violations": [[12, null], [13, null], [14, null], [20, null]]},
               "src/main/java/io/acme/Orders.java": {"percent_covered": 100.0, "violation_lines": [],
                 "covered_lines": [5, 6], "violations": []}},
             "total_num_lines": 10, "total_num_violations": 4, "total_percent_covered": 60,
             "num_changed_lines": 25}""";

    @Test
    void theScoreIsCoveredOverMeasuredChangedLinesAndFilesWithGapsAreIssues() throws IOException {
        QualityDimension coverage = judge(report(REPORT), 0.8, null);

        assertThat(coverage.status()).isEqualTo(QualityStatus.FAIL);
        assertThat(coverage.score()).isEqualTo(60);
        assertThat(coverage.facts()).containsExactly(
                "10 changed lines with coverage in origin/main...HEAD, staged and unstaged changes: 6 covered, 4 not "
                        + "(build/reports/jacoco/test/jacocoTestReport.xml)",
                "not covered: src/main/java/io/acme/Prices.java lines 12-14, 20",
                "minimum 80.0% (forge.quality.diff-coverage.min-score)");
        assertThat(coverage.issues()).containsExactly("src/main/java/io/acme/Prices.java");
        assertThat(judge(report(REPORT), null, null).status()).as("no minimum: a gap warns")
                .isEqualTo(QualityStatus.WARN);
    }

    @Test
    void aTinyOrEmptyDiffIsNotMeasuredRatherThanAPass() throws IOException {
        Path report = report(REPORT);
        assertThat(judge(report, 0.8, 11)).returns(QualityStatus.NOT_MEASURED, QualityDimension::status)
                .satisfies(d -> assertThat(d.facts().getFirst()).isEqualTo("10 changed lines with coverage in "
                        + "origin/main...HEAD, staged and unstaged changes, fewer than min-lines 11"));
        assertThat(judge(report("""
                {"report_name": "x", "diff_name": "d", "src_stats": {}, "total_num_lines": 0,
                 "total_num_violations": 0, "total_percent_covered": 100, "num_changed_lines": 3}"""), null, null)
                .status()).as("diff-cover says 100 % for nothing measured").isEqualTo(QualityStatus.NOT_MEASURED);
        assertThat(judge(dir.resolve("absent.json"), null, null).facts().getFirst()).startsWith("no diff-cover report at");
        assertThat(judge(report("{\"coverage\": 0.5}"), null, null).facts().getFirst())
                .endsWith("is not a diff-cover JSON report (no total_num_lines / src_stats)");
        assertThat(DiffCoverageDimension.ranges(List.of(3, 1, 2, 7, 9, 8, 12))).isEqualTo("1-3, 7-9, 12");
    }
}
