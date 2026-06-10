package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import io.testforge.contract.json.ContractValidationException;
import io.testforge.contract.json.ContractViolation;
import io.testforge.contract.json.FieldType;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.contract.json.MessageContract;
import io.testforge.kafka.KafkaMessage;
import io.testforge.kafka.KafkaMessageBuffer;
import io.testforge.kafka.KafkaProbe;
import io.testforge.kafka.KafkaMessageFilter;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-kafka without a broker: the collector fills the same
 * buffer in real environments, while tests can append messages directly.
 *
 * <p>Contract checks are composition, not a KafkaProbe feature: the probe
 * finds the message, module-contract's validator judges its shape. The two
 * modules stay independently deletable.
 */
@SpringBootTest
class KafkaProbeTest {

    @Autowired
    KafkaMessageBuffer messages;

    @Autowired
    KafkaProbe kafka;

    @Autowired
    JsonContractValidator contracts;

    @BeforeEach
    void clearMessages() {
        messages.clear();
    }

    @Test
    void awaitsBufferedMessageAndValidatesContract() {
        messages.append(new KafkaMessage(
                "partner.events",
                0,
                42,
                "\"request-123\"",
                """
                        {
                          "eventId": "evt-1",
                          "payload": {
                            "status": "accepted",
                            "items": [
                              { "sku": "SKU-1" }
                            ]
                          }
                        }
                        """,
                Instant.parse("2026-06-10T10:00:00Z"),
                Map.of("source", "partner")));

        KafkaMessageFilter filter = KafkaMessageFilter.builder()
                .topic("partner.events")
                .key("request-123")
                .headerEquals("source", "partner")
                .jsonPathEquals("$.payload.status", "accepted")
                .build();

        KafkaMessage message = kafka.awaitMessage(filter);
        contracts.assertValid(message.value(), partnerStatusContract());

        assertThat(message.offset()).isEqualTo(42);
        assertThat(message.value()).contains("accepted");
    }

    @Test
    void reportsContractDriftForCapturedMessage() {
        messages.append(new KafkaMessage(
                "partner.events",
                0,
                43,
                "request-124",
                """
                        {
                          "eventId": 42,
                          "payload": {
                            "items": []
                          }
                        }
                        """,
                Instant.parse("2026-06-10T10:01:00Z"),
                Map.of()));

        KafkaMessageFilter filter = KafkaMessageFilter.builder()
                .topic("partner.events")
                .key("request-124")
                .build();

        KafkaMessage message = kafka.awaitMessage(filter);

        assertThatThrownBy(() -> contracts.assertValid(message.value(), partnerStatusContract()))
                .isInstanceOf(ContractValidationException.class)
                .satisfies(error -> assertThat(((ContractValidationException) error).violations())
                        .extracting(ContractViolation::path, ContractViolation::code)
                        .containsExactly(
                                tuple("$.eventId", "type"),
                                tuple("$.payload.status", "missing"),
                                tuple("$.payload.items[0].sku", "missing")));
    }

    private MessageContract partnerStatusContract() {
        return MessageContract.named("partner-status-event")
                .required("$.eventId", FieldType.STRING)
                .required("$.payload.status", FieldType.STRING)
                .required("$.payload.items[0].sku", FieldType.STRING)
                .build();
    }
}
