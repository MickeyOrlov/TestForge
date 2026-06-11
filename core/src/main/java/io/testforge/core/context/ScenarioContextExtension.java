package io.testforge.core.context;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that ensures {@link ScenarioContext} is cleared after each test.
 */
public final class ScenarioContextExtension implements AfterEachCallback {

    @Override
    public void afterEach(ExtensionContext context) {
        ScenarioContext.clear();
    }
}
