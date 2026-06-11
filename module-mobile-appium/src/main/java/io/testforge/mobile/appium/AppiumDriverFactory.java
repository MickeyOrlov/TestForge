package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import org.openqa.selenium.remote.DesiredCapabilities;

/**
 * Creates platform-appropriate Appium drivers from {@code forge.mobile.appium}
 * properties. Prefer {@link #startSession()} — it wraps the driver in an
 * {@link AppiumSession} so try-with-resources guarantees {@code quit()}.
 */
public class AppiumDriverFactory {

    private final AppiumProperties properties;

    public AppiumDriverFactory(AppiumProperties properties) {
        this.properties = properties;
    }

    /** Driver wrapped for try-with-resources; one session = one test. */
    public AppiumSession startSession() {
        return new AppiumSession(createDriver());
    }

    public AppiumDriver createDriver() {
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability("platformName", properties.platformName());
        caps.setCapability("appium:deviceName", properties.deviceName());
        caps.setCapability("appium:app", properties.appPath());
        caps.setCapability("appium:automationName", properties.automationName());

        URL url = hubUrl();
        if ("iOS".equalsIgnoreCase(properties.platformName())) {
            return new IOSDriver(url, caps);
        }
        return new AndroidDriver(url, caps);
    }

    private URL hubUrl() {
        try {
            return URI.create(properties.hubUrl()).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Appium hub URL: " + properties.hubUrl(), e);
        }
    }
}
