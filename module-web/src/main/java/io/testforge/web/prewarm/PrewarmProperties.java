package io.testforge.web.prewarm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pages to visit once before the suite starts, so UI tests never hit a cold
 * environment (empty SSR/CDN caches, lazy-compiled bundles) and fail on the
 * first navigation timeout.
 *
 * <pre>
 * forge:
 *   prewarm:
 *     enabled: true
 *     urls:
 *       - https://staging.example.test/
 *       - https://staging.example.test/sign-in
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.prewarm")
public record PrewarmProperties(
        boolean enabled,
        List<String> urls,
        Boolean headless,
        Duration navigationTimeout) {

    public PrewarmProperties {
        if (urls == null) {
            urls = List.of();
        }
        if (headless == null) {
            headless = true;
        }
        if (navigationTimeout == null) {
            navigationTimeout = Duration.ofSeconds(40);
        }
    }
}
