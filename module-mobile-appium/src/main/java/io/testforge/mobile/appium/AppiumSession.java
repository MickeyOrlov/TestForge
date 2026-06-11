package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;

/**
 * One mobile session = one test. Closing quits the driver, releasing the
 * device/emulator slot even when the test fails.
 *
 * <pre>{@code
 * try (AppiumSession session = appiumDriverFactory.startSession()) {
 *     AppiumDriver driver = session.driver();
 *     // screen objects work with the driver
 * }
 * }</pre>
 */
public final class AppiumSession implements AutoCloseable {

    private final AppiumDriver driver;

    AppiumSession(AppiumDriver driver) {
        this.driver = driver;
    }

    public AppiumDriver driver() {
        return driver;
    }

    @Override
    public void close() {
        driver.quit();
    }
}
