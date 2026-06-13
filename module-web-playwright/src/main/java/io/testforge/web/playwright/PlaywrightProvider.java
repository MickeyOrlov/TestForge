package io.testforge.web.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;

/**
 * One browser process per JVM; isolation comes from a fresh
 * {@link BrowserContext} per test ({@link #newContext()}), which is cheap.
 */
public class PlaywrightProvider {

    private final PlaywrightProperties properties;
    private final Playwright playwright;
    private final Browser browser;

    public PlaywrightProvider(PlaywrightProperties properties) {
        this.properties = properties;
        this.playwright = Playwright.create();
        BrowserType browserType = switch (properties.browserType().toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            case "chromium" -> playwright.chromium();
            default -> throw new IllegalArgumentException(
                    "Unknown forge.playwright.browser-type '%s'. Use chromium, firefox or webkit."
                            .formatted(properties.browserType()));
        };

        this.browser = browserType.launch(new BrowserType.LaunchOptions()
                .setHeadless(properties.headless()));
    }

    /** Fresh, isolated context with the configured default timeout applied. */
    public BrowserContext newContext() {
        BrowserContext context = browser.newContext();
        context.setDefaultTimeout(properties.defaultTimeout());
        return context;
    }

    public Playwright getPlaywright() {
        return playwright;
    }

    public Browser getBrowser() {
        return browser;
    }

    @PreDestroy
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }
}
