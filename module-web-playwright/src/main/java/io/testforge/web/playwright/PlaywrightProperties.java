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
 *     artifacts-on-failure: true
 *     artifacts-dir: build/playwright-artifacts
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.playwright")
public record PlaywrightProperties(
        Boolean headless,
        Double defaultTimeout,
        String browserType,
        Boolean artifactsOnFailure,
        String artifactsDir) {

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
        if (artifactsOnFailure == null) {
            artifactsOnFailure = true;
        }
        if (artifactsDir == null || artifactsDir.isBlank()) {
            artifactsDir = "build/playwright-artifacts";
        }
    }
}
