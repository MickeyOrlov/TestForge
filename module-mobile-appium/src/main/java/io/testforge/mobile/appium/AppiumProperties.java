package io.testforge.mobile.appium;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.mobile.appium")
public record AppiumProperties(
        String hubUrl,
        String platformName,
        String deviceName,
        String appPath,
        String automationName
) {
    public AppiumProperties {
        if (hubUrl == null) hubUrl = "http://localhost:4723";
        if (platformName == null) platformName = "Android";
        if (automationName == null) automationName = "UiAutomator2";
    }
}
