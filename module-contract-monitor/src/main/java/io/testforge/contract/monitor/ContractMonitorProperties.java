package io.testforge.contract.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "forge.contract-monitor")
public record ContractMonitorProperties(
        Boolean enabled,
        String outputDir,
        String baselineDir,
        Boolean failOnContractViolation,
        Boolean failOnShapeDiff,
        Boolean failOnMissingMessage,
        Integer sampleSize) {

    /** Without a sample size: the default sample. */
    public ContractMonitorProperties(Boolean enabled, String outputDir, String baselineDir,
                                     Boolean failOnContractViolation, Boolean failOnShapeDiff,
                                     Boolean failOnMissingMessage) {
        this(enabled, outputDir, baselineDir, failOnContractViolation, failOnShapeDiff, failOnMissingMessage, null);
    }

    /**
     * Up to {@code sample-size} newest matching messages are validated and
     * their shapes merged (default 20). One message made an optional field
     * that one message happened to omit read as a removed field, and let a
     * violation in the other messages pass.
     */
    @ConstructorBinding
    public ContractMonitorProperties {
        if (sampleSize == null || sampleSize <= 0) {
            sampleSize = 20;
        }
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/contract-monitor/current";
        }
        if (baselineDir == null || baselineDir.isBlank()) {
            baselineDir = "build/contract-monitor/baseline";
        }
        if (failOnContractViolation == null) {
            failOnContractViolation = true;
        }
        if (failOnShapeDiff == null) {
            failOnShapeDiff = true;
        }
        if (failOnMissingMessage == null) {
            failOnMissingMessage = true;
        }
    }
}
