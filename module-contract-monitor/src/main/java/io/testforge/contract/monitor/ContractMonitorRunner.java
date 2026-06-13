package io.testforge.contract.monitor;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    public ContractMonitorRunner(
            List<ContractMonitorCase> cases,
            KafkaProbe kafkaProbe,
            JsonContractValidator validator,
            PayloadShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ContractMonitorProperties properties) {
        this.cases = List.copyOf(cases == null ? List.of() : cases);
        this.kafkaProbe = kafkaProbe;
        this.validator = validator;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.properties = properties;
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
        KafkaMessage message = kafkaProbe.findMessage(monitorCase.filter()).orElse(null);
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
        List<ContractViolation> violations = monitorCase.contract().validate(validator, message.value());

        Map<String, String> shape = Map.of();
        ShapeDiff diff = ShapeDiff.noBaseline();
        String normalizationError = null;
        try {
            shape = new TreeMap<>(normalizer.normalize(message.value()));
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
                shape.isEmpty() ? null : shapeArtifact.toString());
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
                out.append("- offset: ").append(caseReport.offset()).append('\n');
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
}
