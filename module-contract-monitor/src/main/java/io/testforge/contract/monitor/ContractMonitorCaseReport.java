package io.testforge.contract.monitor;

import java.util.List;

import io.testforge.contract.json.ContractViolation;

public record ContractMonitorCaseReport(
        String name,
        boolean failed,
        boolean messageFound,
        String topic,
        Integer partition,
        Long offset,
        String contractName,
        List<ContractViolation> contractViolations,
        ShapeDiff shapeDiff,
        String normalizationError,
        String messageArtifact,
        String shapeArtifact) {

    public ContractMonitorCaseReport {
        contractViolations = List.copyOf(contractViolations == null ? List.of() : contractViolations);
        shapeDiff = shapeDiff == null ? ShapeDiff.noBaseline() : shapeDiff;
    }
}
