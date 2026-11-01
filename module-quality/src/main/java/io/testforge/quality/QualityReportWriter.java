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
                .append("- overall: **").append(report.overall()).append("**")
                .append(report.meanScore() == null ? "" : " (mean score " + report.meanScore() + ")").append("\n\n")
                .append("| Dimension | Status | Score | Formula |\n|---|---|---|---|\n");
        for (QualityDimension dimension : report.dimensions()) {
            out.append("| ").append(dimension.name()).append(" | ").append(dimension.status()).append(" | ")
                    .append(dimension.score() == null ? "—" : dimension.score()).append(" | ")
                    .append(dimension.formula() == null ? "—" : "`" + dimension.formula() + "`").append(" |\n");
        }
        for (QualityDimension dimension : report.dimensions()) {
            out.append("\n## ").append(dimension.name()).append(" — ").append(dimension.status()).append("\n\n");
            dimension.facts().forEach(fact -> out.append("- ").append(fact).append('\n'));
        }
        out.append("\n## Inputs\n\n");
        report.inputs().forEach(input -> out.append("- `").append(input).append("`\n"));
        return out.toString();
    }
}
