package io.testforge.contract.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.ContractProperties;
import io.testforge.contract.json.ContractMappers;
import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.FieldType;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.MessageContract;
import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaMessageFilter;
import io.testforge.kafka.KafkaProbe;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContractMonitorRunnerTest {

    @TempDir
    Path temp;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void writesSuccessfulReportAndShapeSnapshot() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(message("request-1", validPayload()));

        ContractMonitorReport report = runner(buffer).run();

        assertThat(report.healthy()).isTrue();
        assertThat(report.cases()).hasSize(1);
        assertThat(report.cases().getFirst().contractViolations()).isEmpty();
        assertThat(Path.of(report.cases().getFirst().shapeArtifact())).exists();
        assertThat(Path.of(report.reportJson())).exists();
        assertThat(Path.of(report.reportMarkdown())).exists();
    }

    @Test
    void putsContractViolationIntoReport() {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(message("request-1", """
                {
                  "eventId": 42,
                  "payload": { "items": [] }
                }
                """));

        ContractMonitorReport report = runner(buffer).run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.cases().getFirst().contractViolations())
                .extracting(ContractViolation::path)
                .contains("$.eventId", "$.payload.status", "$.payload.items[0].sku");
    }

    @Test
    void missingMessageFailsByDefault() {
        ContractMonitorReport report = runner(new KafkaMessageBuffer(10)).run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.cases().getFirst().messageFound()).isFalse();
    }

    @Test
    void baselineShapeDiffFailsReport() throws Exception {
        KafkaMessageBuffer buffer = new KafkaMessageBuffer(10);
        buffer.append(message("request-1", validPayload()));
        Files.createDirectories(temp.resolve("baseline"));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                temp.resolve("baseline/partner-status.shape.json").toFile(),
                Map.of(
                        "$", "OBJECT",
                        "$.eventId", "STRING",
                        "$.payload.status", "INTEGER",
                        "$.payload.legacy", "STRING"));

        ContractMonitorReport report = runner(buffer).run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.cases().getFirst().shapeDiff().changed())
                .containsExactly(new ShapeDiff.TypeChange("$.payload.status", "INTEGER", "STRING"));
        assertThat(report.cases().getFirst().shapeDiff().removed())
                .containsEntry("$.payload.legacy", "STRING");
    }

    private ContractMonitorRunner runner(KafkaMessageBuffer buffer) {
        return new ContractMonitorRunner(
                List.of(monitorCase()),
                new KafkaProbe(buffer, new Waiter(new WaitProperties(Duration.ofMillis(10), Duration.ofMillis(1))),
                        objectMapper),
                new JsonContractValidator(ContractMappers.strict(), new ContractProperties(false, 100)),
                new PayloadShapeNormalizer(),
                objectMapper,
                new ContractMonitorProperties(
                        true,
                        temp.resolve("current").toString(),
                        temp.resolve("baseline").toString(),
                        true,
                        true,
                        true));
    }

    private ContractMonitorCase monitorCase() {
        return new ContractMonitorCase(
                "partner-status",
                KafkaMessageFilter.builder()
                        .topic("partner.events")
                        .key("request-1")
                        .build(),
                JsonPayloadContract.of(MessageContract.named("partner-status")
                        .required("$.eventId", FieldType.STRING)
                        .required("$.payload.status", FieldType.STRING)
                        .required("$.payload.items[0].sku", FieldType.STRING)
                        .build()));
    }

    private KafkaMessage message(String key, String payload) {
        return new KafkaMessage(
                "partner.events",
                0,
                42,
                key,
                payload,
                Instant.parse("2026-06-10T10:00:00Z"),
                Map.of("source", "partner"));
    }

    private String validPayload() {
        return """
                {
                  "eventId": "evt-1",
                  "payload": {
                    "status": "accepted",
                    "items": [
                      { "sku": "SKU-1" }
                    ]
                  }
                }
                """;
    }
}
