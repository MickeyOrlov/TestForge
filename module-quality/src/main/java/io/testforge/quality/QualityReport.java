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
 */
public record QualityReport(
        String runId,
        String baselineRunId,
        QualityStatus overall,
        Integer meanScore,
        List<QualityDimension> dimensions,
        List<String> inputs,
        String reportMarkdown,
        String reportJson) {

    public QualityReport {
        dimensions = List.copyOf(dimensions);
        inputs = List.copyOf(inputs);
    }

    public Optional<QualityDimension> dimension(String name) {
        return dimensions.stream().filter(d -> d.name().equals(name)).findFirst();
    }

    QualityReport withFiles(String markdown, String json) {
        return new QualityReport(runId, baselineRunId, overall, meanScore, dimensions, inputs, markdown, json);
    }

    QualityReport withDimensions(String baseline, List<QualityDimension> compared) {
        return new QualityReport(runId, baseline, overall, meanScore, compared, inputs, reportMarkdown, reportJson);
    }
}
