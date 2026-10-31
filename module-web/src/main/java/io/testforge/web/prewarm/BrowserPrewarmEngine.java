package io.testforge.web.prewarm;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;

/** Visits pages in headless Chromium, so bundles, SSR and CDN caches all warm. */
public final class BrowserPrewarmEngine implements PrewarmEngine {

    @Override
    public Session open(PrewarmProperties properties) {
        Playwright playwright = Playwright.create();
        try {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(properties.headless()));
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            return new Session() {
                @Override
                public PrewarmVisit visit(String url) {
                    long started = System.currentTimeMillis();
                    try {
                        Response response = page.navigate(url, new Page.NavigateOptions()
                                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(properties.navigationTimeout().toMillis()));
                        try {
                            page.waitForLoadState(LoadState.NETWORKIDLE,
                                    new Page.WaitForLoadStateOptions().setTimeout(10_000));
                        } catch (RuntimeException ignored) {
                            // NETWORKIDLE is best effort: SPAs with long-polling never go idle
                        }
                        return new PrewarmVisit(UrlRedactor.redact(url), response == null ? null : response.status(),
                                UrlRedactor.redact(page.url()), System.currentTimeMillis() - started, null);
                    } catch (RuntimeException e) {
                        return new PrewarmVisit(UrlRedactor.redact(url), null, null,
                                System.currentTimeMillis() - started, firstLine(UrlRedactor.scrub(e.getMessage(), url)));
                    }
                }

                @Override
                public void close() {
                    try {
                        context.close();
                        browser.close();
                    } finally {
                        playwright.close();
                    }
                }
            };
        } catch (RuntimeException e) {
            playwright.close();
            throw e;
        }
    }

    static String firstLine(String message) {
        return message == null ? "unknown error" : message.strip().lines().findFirst().orElse("unknown error");
    }
}
