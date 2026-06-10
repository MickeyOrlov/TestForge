package io.testforge.flow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Guardrails for flow/state-machine execution.
 *
 * <pre>
 * forge:
 *   flow:
 *     timeout: 60s
 *     max-transitions: 100
 *     max-visits-per-state: 5
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.flow")
public record FlowProperties(Duration timeout, int maxTransitions, int maxVisitsPerState) {

    public FlowProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (maxTransitions <= 0) {
            maxTransitions = 100;
        }
        if (maxVisitsPerState <= 0) {
            maxVisitsPerState = 5;
        }
    }
}
