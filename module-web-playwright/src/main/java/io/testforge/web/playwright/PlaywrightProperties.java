package io.testforge.web.playwright;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "testforge.web.playwright")
public record PlaywrightProperties(
        boolean headless,
        Double defaultTimeout,
        String browserType
) {
    public PlaywrightProperties {
        if (browserType == null) {
            browserType = "chromium";
        }
        if (defaultTimeout == null) {
            defaultTimeout = 30000.0;
        }
    }
}
