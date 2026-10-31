package io.testforge.contract.monitor;

import io.testforge.contract.json.ContractViolation;
import java.util.List;

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
        String shapeArtifact,
        int messagesSampled) {

    public ContractMonitorCaseReport(String name, boolean failed, boolean messageFound, String topic,
                                     Integer partition, Long offset, String contractName,
                                     List<ContractViolation> contractViolations, ShapeDiff shapeDiff,
                                     String normalizationError, String messageArtifact, String shapeArtifact) {
        this(name, failed, messageFound, topic, partition, offset, contractName, contractViolations, shapeDiff,
                normalizationError, messageArtifact, shapeArtifact, messageFound ? 1 : 0);
    }

    public ContractMonitorCaseReport {
        contractViolations = List.copyOf(contractViolations == null ? List.of() : contractViolations);
        shapeDiff = shapeDiff == null ? ShapeDiff.noBaseline() : shapeDiff;
    }
}
