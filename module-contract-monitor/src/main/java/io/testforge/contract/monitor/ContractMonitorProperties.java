package io.testforge.contract.monitor;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.contract-monitor")
public record ContractMonitorProperties(
        Boolean enabled,
        String outputDir,
        String baselineDir,
        Boolean failOnContractViolation,
        Boolean failOnShapeDiff,
        Boolean failOnMissingMessage) {

    public ContractMonitorProperties {
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
