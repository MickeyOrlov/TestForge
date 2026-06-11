package io.testforge.web.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;

public class PlaywrightProvider {

    private final Playwright playwright;
    private final Browser browser;

    public PlaywrightProvider(PlaywrightProperties properties) {
        this.playwright = Playwright.create();
        BrowserType browserType = switch (properties.browserType().toLowerCase()) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            default -> playwright.chromium();
        };

        this.browser = browserType.launch(new BrowserType.LaunchOptions()
                .setHeadless(properties.headless()));
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
