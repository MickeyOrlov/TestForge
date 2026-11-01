package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code quality.md} for people, {@code quality.json} for dashboards — the same content — and
 * beside them {@code quality-summary.md} to paste into a pull request, {@code quality.prom} in
 * OpenMetrics text for a metrics scraper or push gateway, and {@code quality.schema.json}
 * describing {@code quality.json}.
 */
final class QualityReportWriter {

    static final String MARKDOWN = "quality.md";
    static final String JSON = "quality.json";
    static final String SUMMARY = "quality-summary.md";
    static final String METRICS = "quality.prom";
    static final String SCHEMA = "quality.schema.json";
    static final int SUMMARY_ISSUES = 5;

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
            Files.writeString(outputDir.resolve(SUMMARY), summary(written));
            Files.writeString(outputDir.resolve(METRICS), metrics(written));
            try (var schema = QualityReportWriter.class.getResourceAsStream(SCHEMA)) {
                Files.write(outputDir.resolve(SCHEMA), schema.readAllBytes());
            }
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
        if (!report.labels().isEmpty()) {
            out.append("- labels: ").append(labels(report)).append('\n');
        }
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
        if (!report.components().isEmpty()) {
            out.append("\n## Components\n\n| Component | Owner | Status | Issues | New |\n|---|---|---|---|---|\n");
            for (QualityReport.Component component : report.components()) {
                out.append("| ").append(component.name()).append(" | ")
                        .append(component.owner() == null ? "—" : component.owner()).append(" | ")
                        .append(component.status()).append(" | ").append(component.issues().size()).append(" | ")
                        .append(component.newIssues()).append(" |\n");
            }
            for (QualityReport.Component component : report.components()) {
                list(out, component.name(), component.issues());
            }
        }
        out.append("\n## Inputs\n\n");
        report.inputs().forEach(input -> out.append("- `").append(input).append("`\n"));
        return out.toString();
    }

    /**
     * The few lines a pull request needs: overall, what is not PASS with its new issues, the
     * components with new issues. The full report stays in {@code quality.md}.
     */
    static String summary(QualityReport report) {
        StringBuilder out = new StringBuilder("### Quality: ").append(report.overall())
                .append(report.meanScore() == null ? "" : " (mean score " + report.meanScore() + ")")
                .append(report.runId() == null ? "" : " — run `" + report.runId() + "`").append("\n\n");
        if (!report.labels().isEmpty()) {
            out.append(labels(report)).append("\n\n");
        }
        List<QualityDimension> attention = report.dimensions().stream()
                .filter(d -> d.status() == QualityStatus.FAIL || d.status() == QualityStatus.WARN).toList();
        if (attention.isEmpty()) {
            out.append("Every measured dimension passed.\n");
        } else {
            out.append("| Dimension | Status | Score | New issues |\n|---|---|---|---|\n");
            for (QualityDimension dimension : attention) {
                List<String> fresh = dimension.newIssues();
                out.append("| ").append(dimension.name()).append(" | ").append(dimension.status()).append(" | ")
                        .append(dimension.score()).append(" | ")
                        .append(fresh.stream().limit(SUMMARY_ISSUES).map(i -> "`" + i.replace("|", "\\|") + "`")
                                .collect(java.util.stream.Collectors.joining(", ")))
                        .append(fresh.size() > SUMMARY_ISSUES ? " (+" + (fresh.size() - SUMMARY_ISSUES) + ")" : "")
                        .append(" |\n");
            }
        }
        report.components().stream().filter(c -> c.newIssues() > 0).forEach(c -> out.append("\n- **")
                .append(c.name()).append("**").append(c.owner() == null ? "" : " (" + c.owner() + ")").append(": ")
                .append(c.newIssues()).append(" new, ").append(c.status()));
        if (report.components().stream().anyMatch(c -> c.newIssues() > 0)) {
            out.append('\n');
        }
        out.append("\nMeasured ").append(report.measured()).append(" of ").append(report.dimensions().size())
                .append(" dimensions").append(notMeasured(report)).append(". Full report: `")
                .append(MARKDOWN).append("`.\n");
        return out.toString();
    }

    /** OpenMetrics text: one gauge family per fact a dashboard plots, labelled by dimension. */
    static String metrics(QualityReport report) {
        StringBuilder out = new StringBuilder();
        java.util.Map<String, String> info = new java.util.TreeMap<>();
        report.labels().forEach((key, value) -> info.put(metricLabel(key), value));
        if (report.runId() != null) {
            info.put("run_id", report.runId());
        }
        info.put("overall", report.overall().name());
        out.append("# TYPE testforge_quality_run info\n# HELP testforge_quality_run The judged run.\n")
                .append("testforge_quality_run_info").append(labelSet(info)).append(" 1\n");
        out.append("# TYPE testforge_quality_mean_score gauge\n");
        if (report.meanScore() != null) {
            out.append("testforge_quality_mean_score ").append(report.meanScore()).append('\n');
        }
        out.append("# TYPE testforge_quality_score gauge\n");
        report.dimensions().stream().filter(d -> d.score() != null).forEach(d -> out.append("testforge_quality_score")
                .append(labelSet(java.util.Map.of("dimension", d.name()))).append(' ').append(d.score()).append('\n'));
        out.append("# TYPE testforge_quality_status stateset\n");
        for (QualityDimension dimension : report.dimensions()) {
            for (QualityStatus status : QualityStatus.values()) {
                out.append("testforge_quality_status").append(labelSet(new java.util.TreeMap<>(java.util.Map.of(
                                "dimension", dimension.name(), "testforge_quality_status", status.name()))))
                        .append(' ').append(dimension.status() == status ? 1 : 0).append('\n');
            }
        }
        out.append("# TYPE testforge_quality_issues gauge\n");
        for (QualityDimension dimension : report.dimensions()) {
            out.append("testforge_quality_issues").append(labelSet(new java.util.TreeMap<>(java.util.Map.of(
                    "dimension", dimension.name(), "kind", "all")))).append(' ').append(dimension.issues().size())
                    .append('\n');
            out.append("testforge_quality_issues").append(labelSet(new java.util.TreeMap<>(java.util.Map.of(
                    "dimension", dimension.name(), "kind", "new")))).append(' ').append(dimension.newIssues().size())
                    .append('\n');
        }
        if (!report.components().isEmpty()) {
            out.append("# TYPE testforge_quality_component_new_issues gauge\n");
            report.components().forEach(c -> out.append("testforge_quality_component_new_issues")
                    .append(labelSet(java.util.Map.of("component", c.name()))).append(' ').append(c.newIssues())
                    .append('\n'));
        }
        return out.append("# EOF\n").toString();
    }

    private static String labels(QualityReport report) {
        return report.labels().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(java.util.stream.Collectors.joining(", "));
    }

    private static String metricLabel(String key) {
        String name = key.replaceAll("[^A-Za-z0-9_]", "_");
        return name.isEmpty() || Character.isDigit(name.charAt(0)) ? "_" + name : name;
    }

    private static String labelSet(java.util.Map<String, String> labels) {
        return labels.entrySet().stream().map(e -> e.getKey() + "=\"" + e.getValue().replace("\\", "\\\\")
                        .replace("\"", "\\\"").replace("\n", "\\n") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
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
