package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.FieldType;
import io.testforge.contract.json.MessageContract;
import io.testforge.contract.monitor.ContractMonitorCase;
import io.testforge.contract.monitor.ContractMonitorReport;
import io.testforge.contract.monitor.ContractMonitorRunner;
import io.testforge.contract.monitor.JsonPayloadContract;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaMessageFilter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@SpringBootTest(properties = {
        "forge.contract-monitor.enabled=true",
        "forge.contract-monitor.output-dir=build/contract-monitor/example-current",
        "forge.contract-monitor.baseline-dir=build/contract-monitor/example-baseline"
})
class ContractMonitorExampleTest {

    private static final Path OUTPUT_DIR = Path.of("build/contract-monitor/example-current");
    private static final Path BASELINE_DIR = Path.of("build/contract-monitor/example-baseline");

    @Autowired
    KafkaMessageBuffer messages;

    @Autowired
    ContractMonitorRunner monitor;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void reset() throws IOException {
        messages.clear();
        deleteIfExists(OUTPUT_DIR);
        deleteIfExists(BASELINE_DIR);
    }

    @Test
    void successfulMonitorRunWritesArtifactsWithoutExternalKafka() throws IOException {
        messages.append(partnerStatusMessage(validPayload()));

        assertThatCode(monitor::assertHealthy).doesNotThrowAnyException();

        ContractMonitorReport report = monitor.run();
        assertThat(report.healthy()).isTrue();
        assertThat(Path.of(report.cases().getFirst().shapeArtifact())).exists();
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("Contract Monitor Report")
                .contains("partner-status");
    }

    @Test
    void driftReportShowsReadableShapeDiff() throws IOException {
        Files.createDirectories(BASELINE_DIR);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                BASELINE_DIR.resolve("partner-status.shape.json").toFile(),
                Map.of(
                        "$", "OBJECT",
                        "$.eventId", "STRING",
                        "$.payload.status", "INTEGER",
                        "$.payload.legacy", "STRING"));
        messages.append(partnerStatusMessage(validPayload()));

        ContractMonitorReport report = monitor.run();

        assertThat(report.healthy()).isFalse();
        assertThat(report.cases().getFirst().shapeDiff().changed())
                .extracting(change -> change.path() + ": " + change.baselineType() + " -> " + change.currentType())
                .containsExactly("$.payload.status: INTEGER -> STRING");
        assertThat(Files.readString(Path.of(report.reportMarkdown())))
                .contains("changed $.payload.status: INTEGER -> STRING")
                .contains("removed $.payload.legacy: STRING");
    }

    @TestConfiguration
    static class MonitorConfig {

        @Bean
        ContractMonitorCase partnerStatusMonitorCase() {
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
    }

    private KafkaMessage partnerStatusMessage(String payload) {
        return new KafkaMessage(
                "partner.events",
                0,
                42,
                "request-1",
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

    private void deleteIfExists(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(this::delete);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
