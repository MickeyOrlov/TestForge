package io.testforge.core.wait;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Polling defaults for {@link Waiter}. Override per environment in
 * application-&lt;environment&gt;.yml:
 *
 * <pre>
 * forge:
 *   wait:
 *     timeout: 30s
 *     poll-interval: 500ms
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.wait")
public record WaitProperties(Duration timeout, Duration pollInterval) {

    public WaitProperties {
        if (timeout == null) {
            timeout = Duration.ofSeconds(30);
        }
        if (pollInterval == null) {
            pollInterval = Duration.ofMillis(500);
        }
    }
}
