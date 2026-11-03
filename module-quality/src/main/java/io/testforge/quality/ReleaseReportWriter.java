package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code release.md} for whoever decides, {@code release.json} and its schema for pipelines. */
final class ReleaseReportWriter {

    static final String MARKDOWN = "release.md";
    static final String JSON = "release.json";
    static final String SCHEMA = "release.schema.json";

    private ReleaseReportWriter() {
    }

    static ReleaseReport write(Path outputDir, ReleaseReport report, ObjectMapper mapper) {
        try {
            Files.createDirectories(outputDir);
            Path markdown = outputDir.resolve(MARKDOWN);
            Path json = outputDir.resolve(JSON);
            ReleaseReport written = report.withFiles(markdown.toString(), json.toString());
            Files.writeString(markdown, render(written));
            mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(json.toFile(), written);
            try (var schema = ReleaseReportWriter.class.getResourceAsStream(SCHEMA)) {
                Files.write(outputDir.resolve(SCHEMA), schema.readAllBytes());
            }
            return written;
        } catch (IOException e) {
            throw new QualityException("Could not write the release report to " + outputDir, e);
        }
    }

    static String render(ReleaseReport report) {
        StringBuilder out = new StringBuilder("### Release: ").append(report.verdict());
        if (!report.blockers().isEmpty()) {
            out.append(" — ").append(report.blockers().size()).append(" blocking");
        } else if (!report.unmeasured().isEmpty()) {
            out.append(" — not measured: ").append(String.join(", ", report.unmeasured().stream()
                    .map(u -> u.split(" ", 2)[0]).toList()));
        }
        out.append(report.runId() == null ? "" : " — run `" + report.runId() + "`").append("\n\n");
        list(out, "Blocking", report.blockers());
        list(out, "Required but not measured", report.unmeasured());
        if (!report.risks().isEmpty()) {
            out.append("**Accepted risks shipped with this release**\n\n| Dimension | Issue | Owner | Reason | Until |\n")
                    .append("|---|---|---|---|---|\n");
            for (ReleaseReport.Risk risk : report.risks()) {
                out.append("| ").append(risk.dimension()).append(" | `").append(risk.issue().replace("|", "\\|"))
                        .append("` | ").append(risk.owner() == null ? "—" : risk.owner()).append(" | ")
                        .append(risk.reason() == null ? "— (no reason given)" : risk.reason().replace("|", "\\|"))
                        .append(" | ").append(risk.until() == null ? "no expiry" : risk.until())
                        .append(risk.expired() ? " **expired**" : "").append(" |\n");
            }
            out.append('\n');
        }
        out.append("| Dimension | Status | Role | Score | Open issues |\n|---|---|---|---|---|\n");
        for (ReleaseReport.Dimension dimension : report.dimensions()) {
            out.append("| ").append(dimension.name()).append(" | ").append(dimension.status()).append(" | ")
                    .append(dimension.role()).append(" | ").append(dimension.score() == null ? "—" : dimension.score())
                    .append(" | ").append(dimension.openIssues()).append(" |\n");
        }
        return out.append("\nOpen issues count whether the baseline knew them or not. Full assessment: `")
                .append(QualityReportWriter.MARKDOWN).append("`.\n").toString();
    }

    private static void list(StringBuilder out, String title, java.util.List<String> lines) {
        if (!lines.isEmpty()) {
            out.append("**").append(title).append("**\n\n");
            lines.forEach(line -> out.append("- ").append(line).append('\n'));
            out.append('\n');
        }
    }
}
