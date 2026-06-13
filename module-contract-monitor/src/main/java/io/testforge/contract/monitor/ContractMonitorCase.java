package io.testforge.contract.monitor;

import io.testforge.kafka.KafkaMessageFilter;

public record ContractMonitorCase(
        String name,
        KafkaMessageFilter filter,
        JsonPayloadContract contract) {

    public ContractMonitorCase {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (filter == null) {
            throw new IllegalArgumentException("filter must not be null");
        }
        if (contract == null) {
            throw new IllegalArgumentException("contract must not be null");
        }
    }
}
