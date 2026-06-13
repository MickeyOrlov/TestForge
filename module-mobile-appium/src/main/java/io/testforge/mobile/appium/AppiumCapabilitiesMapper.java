package io.testforge.mobile.appium;

import java.util.Map;
import org.openqa.selenium.remote.DesiredCapabilities;

public class AppiumCapabilitiesMapper {

    public DesiredCapabilities toCapabilities(ResolvedAppiumDevice device) {
        DesiredCapabilities capabilities = new DesiredCapabilities();
        set(capabilities, "platformName", device.platformName());
        set(capabilities, "appium:deviceName", device.deviceName());
        set(capabilities, "appium:automationName", device.automationName());
        set(capabilities, "appium:app", device.appPath());
        set(capabilities, "appium:appPackage", device.appPackage());
        set(capabilities, "appium:appActivity", device.appActivity());
        set(capabilities, "appium:bundleId", device.bundleId());
        set(capabilities, "appium:platformVersion", device.platformVersion());
        set(capabilities, "appium:udid", device.udid());
        for (Map.Entry<String, Object> entry : device.extraCapabilities().entrySet()) {
            capabilities.setCapability(entry.getKey(), entry.getValue());
        }
        return capabilities;
    }

    private void set(DesiredCapabilities capabilities, String key, String value) {
        if (value != null && !value.isBlank()) {
            capabilities.setCapability(key, value);
        }
    }
}
