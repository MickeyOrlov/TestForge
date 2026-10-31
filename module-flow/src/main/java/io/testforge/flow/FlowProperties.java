package io.testforge.flow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Guardrails for flow/state-machine execution.
 *
 * <pre>
 * forge:
 *   flow:
 *     timeout: 60s
 *     max-transitions: 100
 *     max-visits-per-state: 5
 *     publish-successful-paths: false   # default: only failed runs write flow-path-*.txt
 * </pre>
 *
 * <p>The timeout is checked between steps: a step that blocks is not
 * interrupted, so a step that waits must wait with its own bound
 * ({@code Waiter}, {@code DbWaiter}).
 */
@ConfigurationProperties(prefix = "forge.flow")
public record FlowProperties(Duration timeout, int maxTransitions, int maxVisitsPerState,
                             Boolean publishSuccessfulPaths) {

    /** The guardrails only; successful paths are not published. */
    public FlowProperties(Duration timeout, int maxTransitions, int maxVisitsPerState) {
        this(timeout, maxTransitions, maxVisitsPerState, null);
    }

    @ConstructorBinding
    public FlowProperties {
        if (publishSuccessfulPaths == null) {
            publishSuccessfulPaths = false;
        }
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
