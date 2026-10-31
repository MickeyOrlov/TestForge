package io.testforge.contract.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaProbe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class ContractMonitorRunner {

    private static final TypeReference<Map<String, String>> SHAPE_TYPE = new TypeReference<>() {
    };

    private final List<ContractMonitorCase> cases;
    private final KafkaProbe kafkaProbe;
    private final JsonContractValidator validator;
    private final PayloadShapeNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final ContractMonitorProperties properties;
    private final ArtifactSink artifactSink;

    private static final String SOURCE = "module-contract-monitor";

    public ContractMonitorRunner(
            List<ContractMonitorCase> cases,
            KafkaProbe kafkaProbe,
            JsonContractValidator validator,
            PayloadShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ContractMonitorProperties properties) {
        this(cases, kafkaProbe, validator, normalizer, objectMapper, properties, ArtifactSink.NO_OP);
    }

    public ContractMonitorRunner(
            List<ContractMonitorCase> cases,
            KafkaProbe kafkaProbe,
            JsonContractValidator validator,
            PayloadShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ContractMonitorProperties properties,
            ArtifactSink artifactSink) {
        this.cases = List.copyOf(cases == null ? List.of() : cases);
        this.kafkaProbe = kafkaProbe;
        this.validator = validator;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    public ContractMonitorReport run() {
        Path outputDir = Path.of(properties.outputDir());
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        List<ContractMonitorCaseReport> caseReports = properties.enabled()
                ? runCases(outputDir)
                : List.of();
        boolean healthy = caseReports.stream().noneMatch(ContractMonitorCaseReport::failed);

        ContractMonitorReport report = new ContractMonitorReport(
                properties.enabled(),
                Instant.now().toString(),
                healthy,
                caseReports,
                outputDir.toString(),
                reportJson.toString(),
                reportMarkdown.toString());

        writeReports(report, reportJson, reportMarkdown);
        registerReports(report.enabled(), healthy, caseReports.size(), reportJson, reportMarkdown);
        return report;
    }

    public ContractMonitorReport assertHealthy() {
        ContractMonitorReport report = run();
        if (!report.healthy()) {
            throw new ContractMonitorException(report);
        }
        return report;
    }

    private List<ContractMonitorCaseReport> runCases(Path outputDir) {
        List<ContractMonitorCaseReport> reports = new ArrayList<>();
        for (ContractMonitorCase monitorCase : cases) {
            reports.add(runCase(monitorCase, outputDir));
        }
        return List.copyOf(reports);
    }

    private ContractMonitorCaseReport runCase(ContractMonitorCase monitorCase, Path outputDir) {
        List<KafkaMessage> sample = kafkaProbe.findMessages(monitorCase.filter()).stream()
                .limit(properties.sampleSize()).toList();
        KafkaMessage message = sample.isEmpty() ? null : sample.getFirst();
        if (message == null) {
            return new ContractMonitorCaseReport(
                    monitorCase.name(),
                    properties.failOnMissingMessage(),
                    false,
                    monitorCase.filter().topic(),
                    null,
                    null,
                    monitorCase.contract().name(),
                    List.of(),
                    ShapeDiff.noBaseline(),
                    null,
                    null,
                    null);
        }

        String safeName = safeFileName(monitorCase.name());
        Path messageArtifact = outputDir.resolve(safeName + ".message.json");
        Path shapeArtifact = outputDir.resolve(safeName + ".shape.json");
        List<ContractViolation> violations = sampleViolations(monitorCase, sample);

        Map<String, String> shape = Map.of();
        ShapeDiff diff = ShapeDiff.noBaseline();
        String normalizationError = null;
        try {
            shape = mergedShape(sample);
            writeJson(shapeArtifact, shape);
            diff = diffAgainstBaseline(safeName, shape);
        } catch (RuntimeException e) {
            normalizationError = e.getMessage();
        }

        writeJson(messageArtifact, redactedMessage(message, shape));

        boolean failed = (!violations.isEmpty() && properties.failOnContractViolation())
                || (!diff.empty() && properties.failOnShapeDiff())
                || (normalizationError != null && properties.failOnShapeDiff());

        return new ContractMonitorCaseReport(
                monitorCase.name(),
                failed,
                true,
                message.topic(),
                message.partition(),
                message.offset(),
                monitorCase.contract().name(),
                violations,
                diff,
                normalizationError,
                messageArtifact.toString(),
                shape.isEmpty() ? null : shapeArtifact.toString(),
                sample.size());
    }

    /**
     * Every distinct violation across the sample, once, saying how many sampled
     * messages had it and the newest offset: a producer that breaks one message
     * in five shows up, without twenty copies of the same line.
     */
    private List<ContractViolation> sampleViolations(ContractMonitorCase monitorCase, List<KafkaMessage> sample) {
        Map<String, ContractViolation> first = new java.util.LinkedHashMap<>();
        Map<String, Integer> counts = new java.util.HashMap<>();
        Map<String, Long> offsets = new java.util.HashMap<>();
        for (KafkaMessage message : sample) {
            for (ContractViolation violation : monitorCase.contract().validate(validator, message.value())) {
                String key = violation.path() + "|" + violation.code() + "|" + violation.message();
                first.putIfAbsent(key, violation);
                offsets.putIfAbsent(key, message.offset());
                counts.merge(key, 1, Integer::sum);
            }
        }
        List<ContractViolation> merged = new ArrayList<>();
        first.forEach((key, violation) -> merged.add(sample.size() == 1 ? violation : new ContractViolation(
                violation.contract(), violation.path(), violation.code(), violation.message()
                        + " — in " + counts.get(key) + " of " + sample.size() + " sampled messages, newest at offset "
                        + offsets.get(key))));
        return merged;
    }

    /**
     * The union of the sample's shapes: a path any message has is in it, and a
     * path whose type differs between messages carries all of them,
     * {@code NULL|STRING}.
     */
    private Map<String, String> mergedShape(List<KafkaMessage> sample) {
        Map<String, java.util.Set<String>> types = new TreeMap<>();
        for (KafkaMessage message : sample) {
            normalizer.normalize(message.value()).forEach((path, type) ->
                    types.computeIfAbsent(path, ignored -> new java.util.TreeSet<>()).addAll(List.of(type.split("\\|"))));
        }
        Map<String, String> shape = new TreeMap<>();
        types.forEach((path, seen) -> shape.put(path, String.join("|", seen)));
        return shape;
    }

    private ShapeDiff diffAgainstBaseline(String safeName, Map<String, String> current) {
        Path baseline = Path.of(properties.baselineDir()).resolve(safeName + ".shape.json");
        if (!Files.exists(baseline)) {
            return ShapeDiff.noBaseline();
        }
        try {
            Map<String, String> baselineShape = objectMapper.readValue(baseline.toFile(), SHAPE_TYPE);
            return ShapeDiff.between(baselineShape, current);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read baseline shape " + baseline, e);
        }
    }

    private Map<String, Object> redactedMessage(KafkaMessage message, Map<String, String> shape) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("redacted", true);
        payload.put("shape", shape);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("topic", message.topic());
        envelope.put("partition", message.partition());
        envelope.put("offset", message.offset());
        envelope.put("key", message.key());
        envelope.put("timestamp", message.timestamp().toString());
        envelope.put("headers", message.headers());
        envelope.put("payload", payload);
        return envelope;
    }

    private void writeReports(ContractMonitorReport report, Path reportJson, Path reportMarkdown) {
        writeJson(reportJson, report);
        writeString(reportMarkdown, markdown(report));
    }

    private String markdown(ContractMonitorReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# Contract Monitor Report\n\n");
        out.append("- enabled: ").append(report.enabled()).append('\n');
        out.append("- healthy: ").append(report.healthy()).append('\n');
        out.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        out.append("- cases: ").append(report.cases().size()).append("\n\n");
        for (ContractMonitorCaseReport caseReport : report.cases()) {
            out.append("## ").append(caseReport.name()).append("\n\n");
            out.append("- status: ").append(caseReport.failed() ? "FAILED" : "OK").append('\n');
            out.append("- messageFound: ").append(caseReport.messageFound()).append('\n');
            out.append("- contract: ").append(caseReport.contractName()).append('\n');
            if (caseReport.messageFound()) {
                out.append("- topic: ").append(caseReport.topic()).append('\n');
                out.append("- offset: ").append(caseReport.offset()).append(" (newest)\n");
                out.append("- messagesSampled: ").append(caseReport.messagesSampled()).append('\n');
            }
            appendViolations(out, caseReport);
            appendDiff(out, caseReport.shapeDiff());
            if (caseReport.normalizationError() != null) {
                out.append("- normalizationError: ").append(caseReport.normalizationError()).append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private void appendViolations(StringBuilder out, ContractMonitorCaseReport caseReport) {
        if (caseReport.contractViolations().isEmpty()) {
            out.append("- contractViolations: 0\n");
            return;
        }
        out.append("- contractViolations:\n");
        for (ContractViolation violation : caseReport.contractViolations()) {
            out.append("  - ")
                    .append(violation.path())
                    .append(" [")
                    .append(violation.code())
                    .append("] ")
                    .append(violation.message())
                    .append('\n');
        }
    }

    private void appendDiff(StringBuilder out, ShapeDiff diff) {
        if (!diff.baselinePresent()) {
            out.append("- shapeDiff: baseline missing\n");
            return;
        }
        if (diff.empty()) {
            out.append("- shapeDiff: none\n");
            return;
        }
        out.append("- shapeDiff:\n");
        diff.added().forEach((path, type) -> out.append("  - added ")
                .append(path)
                .append(": ")
                .append(type)
                .append('\n'));
        diff.removed().forEach((path, type) -> out.append("  - removed ")
                .append(path)
                .append(": ")
                .append(type)
                .append('\n'));
        diff.changed().forEach(change -> out.append("  - changed ")
                .append(change.path())
                .append(": ")
                .append(change.baselineType())
                .append(" -> ")
                .append(change.currentType())
                .append('\n'));
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private void writeString(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private String safeFileName(String name) {
        String safe = name.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "contract-monitor-case" : safe;
    }

    /** Best-effort: the run's own report files become entries of the unified run index. */
    private void registerReports(boolean enabled, boolean healthy, int units, Path reportJson, Path reportMarkdown) {
        if (!enabled) {
            return;
        }
        Map<String, String> metadata = Map.of("healthy", String.valueOf(healthy), "cases", String.valueOf(units));
        artifactSink.registerFile(SOURCE, "contract-monitor", "report.json", reportJson, "application/json", metadata);
        artifactSink.registerFile(SOURCE, "contract-monitor", "report.md", reportMarkdown, "text/markdown", metadata);
    }
}
