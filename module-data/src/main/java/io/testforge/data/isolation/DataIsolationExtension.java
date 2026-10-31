package io.testforge.data.isolation;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives each test its own {@link DataIsolation#scopeTag()} for its duration:
 *
 * <pre>{@code
 * @ExtendWith({DataIsolationExtension.class, CleanupExtension.class})
 * class OrdersTest { ... }
 * }</pre>
 *
 * The tag is logged with the test name, so data found on the environment can
 * be traced back to the test that created it.
 */
public class DataIsolationExtension implements BeforeEachCallback, AfterEachCallback {

    private static final Logger log = LoggerFactory.getLogger(DataIsolationExtension.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        DataIsolation.openTest();
        log.info("Test data of {} {} is tagged {}", context.getRequiredTestClass().getSimpleName(),
                context.getDisplayName(), DataIsolation.scopeTag());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        DataIsolation.closeTest();
    }
}
