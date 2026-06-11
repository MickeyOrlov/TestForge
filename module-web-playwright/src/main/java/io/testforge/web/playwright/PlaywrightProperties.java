package io.testforge.web.playwright;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Browser settings for UI tests.
 *
 * <pre>
 * forge:
 *   playwright:
 *     enabled: true          # opt-in: a browser process is expensive
 *     browser-type: chromium # chromium | firefox | webkit
 *     headless: true
 *     default-timeout: 15000
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.playwright")
public record PlaywrightProperties(
        Boolean headless,
        Double defaultTimeout,
        String browserType) {

    public PlaywrightProperties {
        if (headless == null) {
            headless = true;
        }
        if (browserType == null) {
            browserType = "chromium";
        }
        if (defaultTimeout == null) {
            defaultTimeout = 30_000.0;
        }
    }
}
