package io.testforge.web.prewarm;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Visits the configured pages exactly once per JVM. A prewarm failure is
 * logged but never fails the suite: tests against a cold environment are
 * slower, not wrong.
 */
public class PrewarmRunner {

    private static final Logger log = LoggerFactory.getLogger(PrewarmRunner.class);
    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private final PrewarmProperties properties;

    public PrewarmRunner(PrewarmProperties properties) {
        this.properties = properties;
    }

    public void runOnce() {
        if (!properties.enabled() || properties.urls().isEmpty()) {
            return;
        }
        if (!DONE.compareAndSet(false, true)) {
            return;
        }

        long started = System.currentTimeMillis();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(properties.headless()));
            try (BrowserContext context = browser.newContext()) {
                Page page = context.newPage();
                for (String url : properties.urls()) {
                    visit(page, url);
                }
            } finally {
                browser.close();
            }
            log.info("Prewarm finished: {} url(s) in {} ms",
                    properties.urls().size(), System.currentTimeMillis() - started);
        } catch (Exception e) {
            log.warn("Prewarm failed, suite continues against a cold environment: {}", e.getMessage());
        }
    }

    private void visit(Page page, String url) {
        log.info("Prewarm: visiting {}", url);
        page.navigate(url, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(properties.navigationTimeout().toMillis()));
        try {
            page.waitForLoadState(LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout(10_000));
        } catch (RuntimeException ignored) {
            // NETWORKIDLE is best effort: SPAs with long-polling never go idle
        }
    }
}
