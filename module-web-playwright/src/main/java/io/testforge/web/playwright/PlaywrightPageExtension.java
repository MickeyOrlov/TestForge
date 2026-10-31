package io.testforge.web.playwright;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.Tracing;
import io.testforge.artifact.ArtifactSink;
import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.LifecycleMethodExecutionExceptionHandler;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestExecutionExceptionHandler;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Fixture-style page injection: a test declares {@code Page} in its
 * signature and gets a fresh, isolated browser context that is closed
 * automatically after the test.
 *
 * <pre>{@code
 * @SpringBootTest(properties = "forge.playwright.enabled=true")
 * @ExtendWith(PlaywrightPageExtension.class)
 * class CheckoutTest {
 *
 *     @Test
 *     void paysOrder(Page page) { ... }
 * }
 * }</pre>
 */
public class PlaywrightPageExtension
        implements ParameterResolver, TestExecutionExceptionHandler, LifecycleMethodExecutionExceptionHandler {

    private static final Namespace NAMESPACE = Namespace.create(PlaywrightPageExtension.class);
    private static final String FIXTURE_KEY = "fixture";

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Page.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        PlaywrightProvider provider = SpringExtension.getApplicationContext(extensionContext)
                .getBean(PlaywrightProvider.class);
        PlaywrightProperties properties = SpringExtension.getApplicationContext(extensionContext)
                .getBean(PlaywrightProperties.class);
        ArtifactSink artifactSink = SpringExtension.getApplicationContext(extensionContext)
                .getBeanProvider(ArtifactSink.class)
                .getIfAvailable(() -> ArtifactSink.NO_OP);

        Fixture existing = extensionContext.getStore(NAMESPACE).get(FIXTURE_KEY, Fixture.class);
        if (existing != null) {
            return existing.page();
        }

        Fixture fixture = Fixture.open(provider.newContext(), properties, artifactSink);
        // the store closes AutoCloseable resources when the test finishes
        extensionContext.getStore(NAMESPACE).put(FIXTURE_KEY, fixture);
        return fixture.page();
    }

    /**
     * A sign-in in {@code @BeforeEach(Page page)} that fails is the commonest UI
     * failure of all, and it is not a test-method failure: without this it left
     * no screenshot, trace or {@code browser.md}.
     */
    @Override
    public void handleBeforeEachMethodExecutionException(ExtensionContext extensionContext, Throwable throwable)
            throws Throwable {
        handleTestExecutionException(extensionContext, throwable);
    }

    @Override
    public void handleAfterEachMethodExecutionException(ExtensionContext extensionContext, Throwable throwable)
            throws Throwable {
        handleTestExecutionException(extensionContext, throwable);
    }

    @Override
    public void handleTestExecutionException(ExtensionContext extensionContext, Throwable throwable) throws Throwable {
        Fixture fixture = extensionContext.getStore(NAMESPACE).get(FIXTURE_KEY, Fixture.class);
        if (fixture != null) {
            try {
                fixture.captureFailureArtifacts(extensionContext);
            } catch (RuntimeException e) {
                throwable.addSuppressed(e);
            }
        }
        throw throwable;
    }

    private static final class Fixture implements ExtensionContext.Store.CloseableResource {

        private static final String SOURCE = "module-web-playwright";

        private final BrowserContext context;
        private final Page page;
        private final PlaywrightProperties properties;
        private final ArtifactSink artifactSink;
        private final boolean tracingStarted;
        private final BrowserJournal journal;
        private boolean traceStopped;

        private Fixture(BrowserContext context, Page page, PlaywrightProperties properties,
                        ArtifactSink artifactSink, boolean tracingStarted, BrowserJournal journal) {
            this.context = context;
            this.page = page;
            this.properties = properties;
            this.artifactSink = artifactSink;
            this.tracingStarted = tracingStarted;
            this.journal = journal;
        }

        static Fixture open(BrowserContext context, PlaywrightProperties properties, ArtifactSink artifactSink) {
            boolean tracingStarted = false;
            if (properties.artifactsOnFailure()) {
                context.tracing().start(new Tracing.StartOptions()
                        .setScreenshots(true)
                        .setSnapshots(true)
                        .setSources(true));
                tracingStarted = true;
            }
            BrowserJournal journal = new BrowserJournal();
            correlate(context, properties, journal);
            // listen on the context, so pages a test opens itself (popups, new tabs) are heard too
            context.onConsoleMessage(message -> journal.console(message.type(), message.text()));
            context.onResponse(response -> journal.response(
                    response.request().method(), response.url(), response.status()));
            context.onRequestFailed(request -> journal.requestFailed(
                    request.method(), request.url(), request.failure()));
            Page page = context.newPage();
            page.onPageError(journal::pageError);
            context.onPage(opened -> opened.onPageError(journal::pageError));
            return new Fixture(context, page, properties, artifactSink, tracingStarted, journal);
        }

        Page page() {
            return page;
        }

        /**
         * The scenario's correlation id on every browser request to the base URL's
         * origin — the same id {@code module-http} sends, so a UI test's backend log
         * lines, traces and HTTP reports share one value. Other origins (a CDN, an
         * identity provider) never see the header: a custom header on a cross-origin
         * request forces a CORS preflight the third party may refuse.
         */
        private static void correlate(BrowserContext context, PlaywrightProperties properties, BrowserJournal journal) {
            if (!properties.correlation() || properties.baseUrl() == null) {
                return;
            }
            String origin = origin(properties.baseUrl());
            if (origin == null) {
                return;
            }
            String id = ScenarioContext.find(ScenarioKeys.CORRELATION_ID).orElseGet(() -> {
                String generated = "tf-" + UUID.randomUUID();
                ScenarioContext.put(ScenarioKeys.CORRELATION_ID, generated);
                return generated;
            });
            String header = properties.correlationHeader();
            journal.correlation(header, id);
            context.route(url -> origin.equals(origin(url)), route -> {
                Map<String, String> headers = new HashMap<>(route.request().headers());
                // an id the page set itself wins, as with module-http
                if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase(header))) {
                    headers.put(header, id);
                }
                route.resume(new Route.ResumeOptions().setHeaders(headers));
            });
        }

        static String origin(String url) {
            try {
                URI uri = URI.create(url);
                if (uri.getScheme() == null || uri.getHost() == null) {
                    return null;
                }
                String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
                int port = uri.getPort() != -1 ? uri.getPort() : "https".equals(scheme) ? 443 : 80;
                return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private boolean captured;

        void captureFailureArtifacts(ExtensionContext extensionContext) {
            // a failing test and then a failing @AfterEach would capture twice; the first is the story
            if (!properties.artifactsOnFailure() || captured) {
                return;
            }
            captured = true;

            Path directory = artifactDirectory(extensionContext);
            try {
                Files.createDirectories(directory);
                // text first: it needs nothing from the page, so it survives a page that crashed
                Files.writeString(directory.resolve("browser.md"),
                        journal.markdown(String.valueOf(directory.getFileName()), currentUrl(), currentTitle()));
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(directory.resolve("screenshot.png"))
                        .setFullPage(true));
                stopTrace(directory.resolve("trace.zip"));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to capture Playwright failure artifacts in " + directory, e);
            } finally {
                // index whatever made it to disk, including a screenshot taken before a trace failure
                register(directory, "browser.md", "text/markdown", extensionContext);
                register(directory, "screenshot.png", "image/png", extensionContext);
                register(directory, "trace.zip", "application/zip", extensionContext);
            }
        }

        private void register(Path directory, String file, String mediaType, ExtensionContext extensionContext) {
            Path path = directory.resolve(file);
            if (Files.isRegularFile(path)) {
                artifactSink.registerFile(SOURCE, "ui-failure", directory.getFileName() + "/" + file, path, mediaType,
                        // testKey matches the <Class>-<method> name of the HTTP, SQL and Kafka reports of the test
                        Map.of("test", extensionContext.getDisplayName(),
                                "testKey", String.valueOf(directory.getFileName())));
            }
        }

        private String currentUrl() {
            try {
                return page.url();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private String currentTitle() {
            try {
                return page.title();
            } catch (RuntimeException e) {
                return null;
            }
        }

        private void stopTrace(Path path) {
            if (tracingStarted && !traceStopped) {
                context.tracing().stop(new Tracing.StopOptions().setPath(path));
                traceStopped = true;
            }
        }

        private Path artifactDirectory(ExtensionContext extensionContext) {
            String testName = extensionContext.getTestMethod()
                    .map(method -> method.getDeclaringClass().getSimpleName() + "-" + method.getName())
                    .orElseGet(extensionContext::getDisplayName);
            return Path.of(properties.artifactsDir(), safeName(testName));
        }

        private String safeName(String value) {
            String safe = Objects.requireNonNullElse(value, "test")
                    .replaceAll("[^a-zA-Z0-9._-]+", "-")
                    .replaceAll("(^-+|-+$)", "");
            return safe.isBlank() ? "test" : safe;
        }

        @Override
        public void close() {
            if (tracingStarted && !traceStopped) {
                context.tracing().stop();
                traceStopped = true;
            }
            context.close();
        }
    }
}
