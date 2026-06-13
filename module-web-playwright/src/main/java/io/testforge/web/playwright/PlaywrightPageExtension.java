package io.testforge.web.playwright;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Tracing;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
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
public class PlaywrightPageExtension implements ParameterResolver, TestExecutionExceptionHandler {

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

        Fixture existing = extensionContext.getStore(NAMESPACE).get(FIXTURE_KEY, Fixture.class);
        if (existing != null) {
            return existing.page();
        }

        Fixture fixture = Fixture.open(provider.newContext(), properties);
        // the store closes AutoCloseable resources when the test finishes
        extensionContext.getStore(NAMESPACE).put(FIXTURE_KEY, fixture);
        return fixture.page();
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

        private final BrowserContext context;
        private final Page page;
        private final PlaywrightProperties properties;
        private final boolean tracingStarted;
        private boolean traceStopped;

        private Fixture(BrowserContext context, Page page, PlaywrightProperties properties, boolean tracingStarted) {
            this.context = context;
            this.page = page;
            this.properties = properties;
            this.tracingStarted = tracingStarted;
        }

        static Fixture open(BrowserContext context, PlaywrightProperties properties) {
            boolean tracingStarted = false;
            if (properties.artifactsOnFailure()) {
                context.tracing().start(new Tracing.StartOptions()
                        .setScreenshots(true)
                        .setSnapshots(true)
                        .setSources(true));
                tracingStarted = true;
            }
            return new Fixture(context, context.newPage(), properties, tracingStarted);
        }

        Page page() {
            return page;
        }

        void captureFailureArtifacts(ExtensionContext extensionContext) {
            if (!properties.artifactsOnFailure()) {
                return;
            }

            Path directory = artifactDirectory(extensionContext);
            try {
                Files.createDirectories(directory);
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(directory.resolve("screenshot.png"))
                        .setFullPage(true));
                stopTrace(directory.resolve("trace.zip"));
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to capture Playwright failure artifacts in " + directory, e);
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
