package io.testforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Coverage of the lines a change touched, from what diff-cover already computed: its
 * {@code --json-report}, built from the JaCoCo XML and {@code git diff} against the target branch.
 * Intersecting a diff with a coverage report is diff-cover's job; this only reads the result.
 *
 * <p>diff-cover calls a change without measured lines 100 % covered; here it is NOT MEASURED, and so
 * is one with fewer than {@code min-lines} measured lines — a tiny diff says little either way.
 * Issues are files with uncovered changed lines: their lines move with every edit, the file does not.
 */
final class DiffCoverageDimension {

    private DiffCoverageDimension() {
    }

    static QualityDimension of(QualityProperties.DiffCoverageProperties properties, ObjectMapper mapper) {
        return of(properties, mapper, null);
    }

    /** As above; a report older than {@code notBefore} is an earlier build's and leaves it NOT MEASURED. */
    static QualityDimension of(QualityProperties.DiffCoverageProperties properties, ObjectMapper mapper,
                               java.time.Instant notBefore) {
        Path file = Path.of(properties.report());
        if (!Files.isRegularFile(file)) {
            return QualityDimension.notMeasured(QualityDimension.DIFF_COVERAGE, "no diff-cover report at " + file
                    + " (diff-cover <jacoco.xml> --compare-branch=origin/main --format json:" + file + ")");
        }
        String stale = InputFreshness.stale(file, notBefore);
        if (stale != null) {
            return QualityDimension.notMeasured(QualityDimension.DIFF_COVERAGE, stale);
        }
        JsonNode report;
        try {
            report = mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new QualityException("Could not read the diff-cover report " + file, e);
        }
        if (!report.path("total_num_lines").isNumber() || !report.path("src_stats").isObject()) {
            return QualityDimension.notMeasured(QualityDimension.DIFF_COVERAGE, file + " is not a diff-cover JSON "
                    + "report (no total_num_lines / src_stats)");
        }
        int measured = report.path("total_num_lines").asInt();
        int uncovered = report.path("total_num_violations").asInt();
        String diff = report.path("diff_name").asText("the diff");
        if (measured < properties.minLines()) {
            return QualityDimension.notMeasured(QualityDimension.DIFF_COVERAGE, measured + " changed lines with "
                    + "coverage in " + diff + ", fewer than min-lines " + properties.minLines());
        }
        List<String> issues = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        double share = (double) (measured - uncovered) / measured;
        String coverage = report.path("report_name").asText("");
        facts.add(measured + " changed lines with coverage in " + diff + ": " + (measured - uncovered) + " covered, "
                + uncovered + " not" + (coverage.isEmpty() || coverage.equals("XML") ? "" : " (" + coverage + ")"));
        report.path("src_stats").properties().forEach(source -> {
            List<Integer> lines = new ArrayList<>();
            source.getValue().path("violation_lines").forEach(line -> lines.add(line.asInt()));
            if (!lines.isEmpty()) {
                issues.add(source.getKey());
                if (issues.size() <= QualityAssessor.LISTED) {
                    facts.add("not covered: " + source.getKey() + " lines " + ranges(lines));
                }
            }
        });
        if (issues.size() > QualityAssessor.LISTED) {
            facts.add("… and " + (issues.size() - QualityAssessor.LISTED) + " more files");
        }
        if (properties.minScore() != null) {
            facts.add("minimum " + String.format(Locale.ROOT, "%.1f%%", 100 * properties.minScore())
                    + " (forge.quality.diff-coverage.min-score)");
        }
        QualityStatus status = uncovered == 0 ? QualityStatus.PASS
                : properties.minScore() != null && share < properties.minScore() ? QualityStatus.FAIL
                : QualityStatus.WARN;
        return new QualityDimension(QualityDimension.DIFF_COVERAGE, status, (int) Math.round(100 * share),
                "100 × covered / measured changed lines", facts).withIssues(issues);
    }

    /** {@code [12, 13, 14, 20]} as {@code 12-14, 20}. */
    static String ranges(List<Integer> lines) {
        List<Integer> sorted = lines.stream().sorted().distinct().toList();
        List<String> ranges = new ArrayList<>();
        int start = sorted.getFirst();
        int end = start;
        for (int line : sorted.subList(1, sorted.size())) {
            if (line == end + 1) {
                end = line;
                continue;
            }
            ranges.add(start == end ? String.valueOf(start) : start + "-" + end);
            start = line;
            end = line;
        }
        ranges.add(start == end ? String.valueOf(start) : start + "-" + end);
        return String.join(", ", ranges);
    }
}
