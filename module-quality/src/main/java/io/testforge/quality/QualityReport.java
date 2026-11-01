package io.testforge.quality;

import java.util.List;
import java.util.Optional;

/**
 * What the assessment concluded about one run.
 *
 * @param runId     the run index it read, or {@code null} when there was none
 * @param overall   the worst status among measured dimensions; {@code NOT_MEASURED} when none was
 * @param meanScore the unweighted mean of measured scores, for trend lines; {@code null} when none was
 * @param inputs    the files and directories read, so a reader can check the numbers
 * @param baselineRunId the run the comparison is against, {@code null} without a baseline
 * @param notes     what the history suggests to a reader — never acted on by the assessment
 */
@com.fasterxml.jackson.annotation.JsonPropertyOrder({"runId", "baselineRunId", "overall", "meanScore", "measured",
        "notes", "dimensions", "inputs", "reportMarkdown", "reportJson"})
public record QualityReport(
        String runId,
        String baselineRunId,
        QualityStatus overall,
        Integer meanScore,
        List<QualityDimension> dimensions,
        List<String> inputs,
        String reportMarkdown,
        String reportJson,
        List<String> notes) {

    public QualityReport {
        dimensions = List.copyOf(dimensions);
        inputs = List.copyOf(inputs);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public QualityReport(String runId, String baselineRunId, QualityStatus overall, Integer meanScore,
                         List<QualityDimension> dimensions, List<String> inputs, String reportMarkdown,
                         String reportJson) {
        this(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown, reportJson, List.of());
    }

    /** How many dimensions this run could actually judge, of all of them. */
    public int measured() {
        return (int) dimensions.stream().filter(d -> d.status() != QualityStatus.NOT_MEASURED).count();
    }

    public Optional<QualityDimension> dimension(String name) {
        return dimensions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    QualityReport withFiles(String markdown, String json) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, markdown, json, notes);
    }

    QualityReport withDimensions(String baseline, List<QualityDimension> compared) {
        return new QualityReport(runId, baseline, overall, meanScore, compared, inputs, reportMarkdown, reportJson,
                notes);
    }

    QualityReport withNotes(List<String> added) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, reportMarkdown,
                reportJson, added);
    }
}
