package io.testforge.mobile.appium;

import java.net.URI;
import java.util.Map;

public record ResolvedAppiumDevice(
        String deviceId,
        URI hubUrl,
        String platformName,
        String deviceName,
        String automationName,
        String appPath,
        String appPackage,
        String appActivity,
        String bundleId,
        String udid,
        String platformVersion,
        Map<String, Object> extraCapabilities) {

    public ResolvedAppiumDevice {
        if (deviceId == null || deviceId.isBlank()) {
            throw new IllegalArgumentException("deviceId must not be blank");
        }
        if (hubUrl == null) {
            throw new IllegalArgumentException("hubUrl must not be null");
        }
        extraCapabilities = Map.copyOf(extraCapabilities == null ? Map.of() : extraCapabilities);
    }
}
