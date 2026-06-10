package io.testforge.contract;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Defaults for contract validation.
 *
 * <pre>
 * forge:
 *   contract:
 *     fail-fast: false
 *     max-violations: 100
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.contract")
public record ContractProperties(boolean failFast, int maxViolations) {

    public ContractProperties {
        if (maxViolations <= 0) {
            maxViolations = 100;
        }
    }
}
