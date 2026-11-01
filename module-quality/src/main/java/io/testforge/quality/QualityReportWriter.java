package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code quality.md} for people, {@code quality.json} for dashboards — the same content. */
final class QualityReportWriter {

    static final String MARKDOWN = "quality.md";
    static final String JSON = "quality.json";

    private QualityReportWriter() {
    }

    static QualityReport write(Path outputDir, QualityReport report, ObjectMapper mapper) {
        try {
            Files.createDirectories(outputDir);
            Path markdown = outputDir.resolve(MARKDOWN);
            Path json = outputDir.resolve(JSON);
            QualityReport written = report.withFiles(markdown.toString(), json.toString());
            Files.writeString(markdown, render(written));
            mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), written);
            return written;
        } catch (IOException e) {
            throw new QualityException("Could not write the quality report to " + outputDir, e);
        }
    }

    static String render(QualityReport report) {
        StringBuilder out = new StringBuilder("# Quality assessment\n\n")
                .append("- run: ").append(report.runId() == null ? "(no run index)" : report.runId()).append('\n')
                .append("- baseline: ").append(report.baselineRunId() == null ? "none — every issue counts as new"
                        : report.baselineRunId()).append('\n')
                .append("- overall: **").append(report.overall()).append("**")
                .append(report.meanScore() == null ? "" : " (mean score " + report.meanScore() + ")").append('\n')
                .append("- measured: ").append(report.measured()).append(" of ").append(report.dimensions().size())
                .append(" dimensions").append(notMeasured(report)).append('\n');
        report.notes().forEach(note -> out.append("- note: ").append(note).append('\n'));
        out.append('\n')
                .append("| Dimension | Status | Score | Baseline | New issues | Formula |\n|---|---|---|---|---|---|\n");
        for (QualityDimension dimension : report.dimensions()) {
            QualityDelta delta = dimension.delta();
            out.append("| ").append(dimension.name()).append(" | ").append(dimension.status()).append(" | ")
                    .append(dimension.score() == null ? "—" : dimension.score()).append(" | ")
                    .append(delta == null || delta.baselineScore() == null ? "—" : delta.baselineScore()).append(" | ")
                    .append(dimension.newIssues().size()).append(" | ")
                    .append(dimension.formula() == null ? "—" : "`" + dimension.formula() + "`").append(" |\n");
        }
        for (QualityDimension dimension : report.dimensions()) {
            out.append("\n## ").append(dimension.name()).append(" — ").append(dimension.status()).append("\n\n");
            dimension.facts().forEach(fact -> out.append("- ").append(fact).append('\n'));
            QualityDelta delta = dimension.delta();
            if (delta != null) {
                list(out, "new", delta.newIssues());
                list(out, "accepted", delta.acceptedIssues());
                list(out, "known since the baseline", delta.knownIssues());
                list(out, "resolved since the baseline", delta.resolvedIssues());
            }
        }
        out.append("\n## Inputs\n\n");
        report.inputs().forEach(input -> out.append("- `").append(input).append("`\n"));
        return out.toString();
    }

    private static String notMeasured(QualityReport report) {
        java.util.List<String> names = report.dimensions().stream()
                .filter(d -> d.status() == QualityStatus.NOT_MEASURED).map(QualityDimension::name).toList();
        return names.isEmpty() ? "" : " — not measured: " + String.join(", ", names);
    }

    private static void list(StringBuilder out, String label, java.util.List<String> issues) {
        if (!issues.isEmpty()) {
            out.append("- ").append(label).append(" (").append(issues.size()).append("): ")
                    .append(issues.stream().map(i -> "`" + i + "`").collect(java.util.stream.Collectors.joining(", ")))
                    .append('\n');
        }
    }
}
