package io.testforge.mobile.appium;

import io.appium.java_client.AppiumDriver;
import io.appium.java_client.android.AndroidDriver;
import io.appium.java_client.ios.IOSDriver;
import java.net.MalformedURLException;
import java.net.URL;
import org.openqa.selenium.remote.DesiredCapabilities;

public class AppiumDriverFactory {

    private final AppiumProperties properties;

    public AppiumDriverFactory(AppiumProperties properties) {
        this.properties = properties;
    }

    public AppiumDriver createDriver() {
        DesiredCapabilities caps = new DesiredCapabilities();
        caps.setCapability("platformName", properties.platformName());
        caps.setCapability("appium:deviceName", properties.deviceName());
        caps.setCapability("appium:app", properties.appPath());
        caps.setCapability("appium:automationName", properties.automationName());

        try {
            URL url = new URL(properties.hubUrl());
            if ("iOS".equalsIgnoreCase(properties.platformName())) {
                return new IOSDriver(url, caps);
            }
            return new AndroidDriver(url, caps);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid Appium hub URL: " + properties.hubUrl(), e);
        }
    }
}
