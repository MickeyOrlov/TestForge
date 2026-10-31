package io.testforge.web.prewarm;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 *
 * @param failWhenUnreachable stop the suite at startup when not one page
 *        answers (every visit failed or got a 5xx): the environment is down,
 *        and every test would fail slowly for that one reason. Default
 *        {@code false} — prewarm then only warns
 * @param mode how pages are visited: {@code browser} (default) runs them in
 *        headless Chromium, warming client bundles too; {@code http} sends
 *        plain GETs and needs no browser download
 */
@ConfigurationProperties(prefix = "forge.prewarm")
public record PrewarmProperties(
        boolean enabled,
        List<String> urls,
        Boolean headless,
        Duration navigationTimeout,
        Boolean failWhenUnreachable,
        Mode mode) {

    /** How pages are visited. */
    public enum Mode { BROWSER, HTTP }

    @ConstructorBinding
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
        if (failWhenUnreachable == null) {
            failWhenUnreachable = false;
        }
        if (mode == null) {
            mode = Mode.BROWSER;
        }
    }

    public PrewarmProperties(boolean enabled, List<String> urls, Boolean headless, Duration navigationTimeout,
                             Boolean failWhenUnreachable) {
        this(enabled, urls, headless, navigationTimeout, failWhenUnreachable, null);
    }

    public PrewarmProperties(boolean enabled, List<String> urls, Boolean headless, Duration navigationTimeout) {
        this(enabled, urls, headless, navigationTimeout, null, null);
    }
}
