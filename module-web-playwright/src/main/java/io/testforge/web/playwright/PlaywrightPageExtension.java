package io.testforge.web.playwright;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
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
public class PlaywrightPageExtension implements ParameterResolver {

    private static final Namespace NAMESPACE = Namespace.create(PlaywrightPageExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return parameterContext.getParameter().getType() == Page.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        PlaywrightProvider provider = SpringExtension.getApplicationContext(extensionContext)
                .getBean(PlaywrightProvider.class);

        BrowserContext context = provider.newContext();
        // the store closes AutoCloseable resources when the test finishes
        extensionContext.getStore(NAMESPACE)
                .put(extensionContext.getUniqueId(), (ExtensionContext.Store.CloseableResource) context::close);
        return context.newPage();
    }
}
