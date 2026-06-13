package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One mobile session = one test. Closing quits the driver, releasing the
 * device/emulator slot even when the test fails.
 */
public final class AppiumSession implements AutoCloseable {

    private final AppiumDriver driver;
    private final String deviceId;
    private final ResolvedAppiumDevice device;
    private final Path artifactsDir;
    private final AppiumArtifactCollector artifactCollector;

    public AppiumSession(
            AppiumDriver driver,
            ResolvedAppiumDevice device,
            Path artifactsDir,
            AppiumArtifactCollector artifactCollector) {
        this.driver = driver;
        this.device = device;
        this.deviceId = device.deviceId();
        this.artifactsDir = artifactsDir;
        this.artifactCollector = artifactCollector;
    }

    public AppiumDriver driver() {
        return driver;
    }

    public String deviceId() {
        return deviceId;
    }

    public ResolvedAppiumDevice device() {
        return device;
    }

    public Path artifactsDir() {
        return artifactsDir;
    }

    public Optional<Path> captureFailureArtifacts(String testName) {
        return artifactCollector.capture(this, testName);
    }

    @Override
    public void close() {
        driver.quit();
    }
}
