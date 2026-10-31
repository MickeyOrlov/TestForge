package io.testforge.web.playwright;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import java.util.Locale;
import java.util.Map;

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
        this.playwright = properties.downloadBrowsers()
                ? Playwright.create()
                : Playwright.create(new Playwright.CreateOptions()
                        .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
        // Locale.ROOT: under a Turkish locale "CHROMIUM".toLowerCase() is "chromıum"
        BrowserType browserType = switch (properties.browserType().toLowerCase(Locale.ROOT)) {
            case "firefox" -> playwright.firefox();
            case "webkit" -> playwright.webkit();
            case "chromium" -> playwright.chromium();
            default -> throw new IllegalArgumentException(
                    "Unknown forge.playwright.browser-type '%s'. Use chromium, firefox or webkit."
                            .formatted(properties.browserType()));
        };

        try {
            this.browser = browserType.launch(new BrowserType.LaunchOptions()
                    .setHeadless(properties.headless()));
        } catch (RuntimeException e) {
            playwright.close();
            throw new IllegalStateException("Could not launch " + properties.browserType() + ": "
                    + firstLine(e.getMessage()) + ". Install the browser into the runner image with "
                    + "./gradlew :module-web-playwright:playwrightInstall (or `playwright install "
                    + properties.browserType() + "`).", e);
        }
    }

    private static String firstLine(String message) {
        return message == null ? "unknown error" : message.strip().lines().findFirst().orElse("unknown error");
    }

    /** Fresh, isolated context with the configured default timeout applied. */
    public BrowserContext newContext() {
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        if (properties.baseUrl() != null) {
            options.setBaseURL(properties.baseUrl());
        }
        BrowserContext context = browser.newContext(options);
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
