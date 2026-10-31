package io.testforge.web.playwright;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 *     base-url: https://staging.example.test
 *     correlation: true
 *     correlation-header: X-Request-Id
 * </pre>
 *
 * @param baseUrl           what relative navigation ({@code page.navigate("/checkout")}) resolves against
 * @param correlation       send the scenario's correlation id on the browser's requests to the
 *                          {@code base-url} origin (default {@code true}; needs {@code base-url})
 * @param correlationHeader the header it travels in; the same default as {@code module-http}
 */
@ConfigurationProperties(prefix = "forge.playwright")
public record PlaywrightProperties(
        Boolean headless,
        Double defaultTimeout,
        String browserType,
        Boolean artifactsOnFailure,
        String artifactsDir,
        String baseUrl,
        Boolean correlation,
        String correlationHeader) {

    @ConstructorBinding
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
        if (baseUrl != null && baseUrl.isBlank()) {
            baseUrl = null;
        }
        if (correlation == null) {
            correlation = true;
        }
        if (correlationHeader == null || correlationHeader.isBlank()) {
            correlationHeader = "X-Request-Id";
        }
    }

    public PlaywrightProperties(Boolean headless, Double defaultTimeout, String browserType,
                                Boolean artifactsOnFailure, String artifactsDir) {
        this(headless, defaultTimeout, browserType, artifactsOnFailure, artifactsDir, null, null, null);
    }
}
