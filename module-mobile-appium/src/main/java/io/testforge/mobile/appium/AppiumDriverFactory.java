package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import org.openqa.selenium.remote.DesiredCapabilities;

/**
 * Creates platform-appropriate Appium drivers from resolved device
 * configuration. Prefer {@link #startSession()} or
 * {@link #startSession(String)} so try-with-resources guarantees
 * {@code quit()}.
 */
public class AppiumDriverFactory {

    private final AppiumDeviceRegistry devices;
    private final AppiumCapabilitiesMapper capabilitiesMapper;
    private final AppiumArtifactCollector artifactCollector;
    private final Path artifactsDir;

    public AppiumDriverFactory(AppiumProperties properties) {
        this(
                new AppiumDeviceRegistry(properties),
                new AppiumCapabilitiesMapper(),
                new AppiumArtifactCollector(properties),
                Path.of(properties.artifactsDir()));
    }

    public AppiumDriverFactory(
            AppiumDeviceRegistry devices,
            AppiumCapabilitiesMapper capabilitiesMapper,
            AppiumArtifactCollector artifactCollector,
            Path artifactsDir) {
        this.devices = devices;
        this.capabilitiesMapper = capabilitiesMapper;
        this.artifactCollector = artifactCollector;
        this.artifactsDir = artifactsDir;
    }

    /** Driver wrapped for try-with-resources; one session = one test. */
    public AppiumSession startSession() {
        return startSession(devices.resolveDefault());
    }

    public AppiumSession startSession(String deviceId) {
        return startSession(devices.resolve(deviceId));
    }

    public AppiumSession startSession(ResolvedAppiumDevice device) {
        return new AppiumSession(createDriver(device), device, artifactsDir, artifactCollector);
    }

    public AppiumDriver createDriver() {
        return createDriver(devices.resolveDefault());
    }

    public AppiumDriver createDriver(String deviceId) {
        return createDriver(devices.resolve(deviceId));
    }

    public AppiumDriver createDriver(ResolvedAppiumDevice device) {
        return newDriver(device, capabilitiesMapper.toCapabilities(device));
    }

    protected AppiumDriver newDriver(ResolvedAppiumDevice device, DesiredCapabilities capabilities) {
        URL url = hubUrl(device.hubUrl());
        if ("iOS".equalsIgnoreCase(device.platformName())) {
            return new IOSDriver(url, capabilities);
        }
        if ("Android".equalsIgnoreCase(device.platformName())) {
            return new AndroidDriver(url, capabilities);
        }
        throw new IllegalArgumentException(
                "Unsupported Appium platform '%s' for device '%s'. Use Android or iOS."
                        .formatted(device.platformName(), device.deviceId()));
    }

    private URL hubUrl(URI hubUrl) {
        try {
            return hubUrl.toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Appium hub URL: " + hubUrl, e);
        }
    }
}
