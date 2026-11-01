package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Judges a finished run: reads its JUnit XML and run index, scores the
 * dimensions, writes {@code quality.md} / {@code quality.json} and registers
 * them in the current run's index.
 *
 * <pre>{@code
 * @Test
 * void theBuildIsAcceptable() {
 *     qualityRunner.assertAcceptable();
 * }
 * }</pre>
 *
 * Run it in a CI step after the test jobs, with their results in place.
 */
public class QualityRunner {

    static final String SOURCE = "module-quality";

    private final QualityProperties properties;
    private final ObjectMapper mapper;
    private final ArtifactSink sink;

    public QualityRunner(QualityProperties properties, ObjectMapper mapper, ArtifactSink sink) {
        this.properties = properties;
        this.mapper = mapper;
        this.sink = sink == null ? ArtifactSink.NO_OP : sink;
    }

    /** Assesses and writes the report; never fails on a bad product, only on unreadable inputs. */
    public QualityReport run() {
        List<Path> junit = properties.junitResults().stream().map(Path::of).toList();
        List<JunitResults.TestCase> tests = JunitResults.read(junit);
        Path artifacts = Path.of(properties.artifactsRoot());
        RunIndex index = RunIndex.find(artifacts, properties.runId(), mapper).orElse(null);
        List<String> inputs = new ArrayList<>(properties.junitResults());
        inputs.add(index == null ? properties.artifactsRoot() + " (no run index)" : index.manifest().toString());
        QualityReport report = QualityReportWriter.write(Path.of(properties.outputDir()),
                QualityAssessor.assess(index == null ? null : index.runId(), tests, index, inputs, properties),
                mapper);
        Map<String, String> metadata = Map.of("overall", report.overall().name());
        sink.registerFile(SOURCE, "quality", QualityReportWriter.MARKDOWN, Path.of(report.reportMarkdown()),
                "text/markdown", metadata);
        sink.registerFile(SOURCE, "quality", QualityReportWriter.JSON, Path.of(report.reportJson()),
                "application/json", metadata);
        return report;
    }

    /**
     * {@link #run()}, then fails when a {@code fail-on} dimension FAILed or
     * nothing could be measured. Fails closed while
     * {@code forge.quality.enabled} is false: a gate job that forgot it is
     * told, not silently green.
     */
    public QualityReport assertAcceptable() {
        if (!properties.enabled()) {
            throw new QualityException("The quality gate is disabled: set forge.quality.enabled=true in the job "
                    + "that runs it (run() reports without the gate)");
        }
        QualityReport report = run();
        if (report.overall() == QualityStatus.NOT_MEASURED) {
            throw new QualityException("Nothing to assess: no executed tests in " + properties.junitResults()
                    + " and no run index under " + properties.artifactsRoot() + ". See " + report.reportMarkdown());
        }
        List<QualityDimension> failed = report.dimensions().stream()
                .filter(d -> d.status() == QualityStatus.FAIL && properties.failOn().contains(d.name()))
                .toList();
        if (!failed.isEmpty()) {
            throw new QualityException("Quality gate failed on " + failed.stream()
                    .map(d -> d.name() + " (" + d.facts().getFirst() + ")").collect(Collectors.joining("; "))
                    + ". See " + report.reportMarkdown());
        }
        return report;
    }
}
