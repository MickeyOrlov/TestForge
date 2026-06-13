package io.testforge.mobile.appium;

import java.net.URI;
import java.util.Map;
import java.util.stream.Collectors;

public class AppiumDeviceRegistry {

    static final String LEGACY_DEVICE_ID = "legacy";

    private final AppiumProperties properties;

    public AppiumDeviceRegistry(AppiumProperties properties) {
        this.properties = properties;
    }

    public ResolvedAppiumDevice resolveDefault() {
        if (hasText(properties.defaultDevice())) {
            return resolve(properties.defaultDevice());
        }
        if (hasLegacyFlatProps()) {
            return resolveLegacy();
        }
        throw new IllegalArgumentException(
                "No Appium default device configured. Set forge.mobile.appium.default-device, "
                        + "use @MobileDevice(\"id\"), or configure legacy flat props.");
    }

    public ResolvedAppiumDevice resolve(String deviceId) {
        if (!hasText(deviceId)) {
            return resolveDefault();
        }

        AppiumProperties.DeviceProperties device = properties.devices().get(deviceId);
        if (device == null) {
            String known = properties.devices().keySet().stream()
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Unknown Appium device id '%s'. Configured devices: [%s]"
                    .formatted(deviceId, known));
        }
        return validate(new ResolvedAppiumDevice(
                deviceId,
                hubUri(),
                firstText(device.platformName(), properties.platformName()),
                device.deviceName(),
                firstText(device.automationName(), defaultAutomationName(device.platformName())),
                device.appPath(),
                device.appPackage(),
                device.appActivity(),
                device.bundleId(),
                device.udid(),
                device.platformVersion(),
                device.extraCapabilities()));
    }

    private ResolvedAppiumDevice resolveLegacy() {
        return validate(new ResolvedAppiumDevice(
                LEGACY_DEVICE_ID,
                hubUri(),
                properties.platformName(),
                properties.deviceName(),
                properties.automationName(),
                properties.appPath(),
                properties.appPackage(),
                properties.appActivity(),
                properties.bundleId(),
                properties.udid(),
                properties.platformVersion(),
                Map.of()));
    }

    private ResolvedAppiumDevice validate(ResolvedAppiumDevice device) {
        if (!hasText(device.platformName())) {
            throw new IllegalArgumentException("Appium device '%s' is missing platform-name"
                    .formatted(device.deviceId()));
        }
        if (isAndroid(device)) {
            if (!hasText(device.appPath()) && !(hasText(device.appPackage()) && hasText(device.appActivity()))) {
                throw new IllegalArgumentException(
                        "Appium device '%s' is missing required Android fields: set app-path or app-package + app-activity"
                                .formatted(device.deviceId()));
            }
            return device;
        }
        if (isIos(device)) {
            if (!hasText(device.appPath()) && !hasText(device.bundleId())) {
                throw new IllegalArgumentException(
                        "Appium device '%s' is missing required iOS fields: set app-path or bundle-id"
                                .formatted(device.deviceId()));
            }
            return device;
        }
        throw new IllegalArgumentException(
                "Unsupported Appium platform '%s' for device '%s'. Use Android or iOS."
                        .formatted(device.platformName(), device.deviceId()));
    }

    private URI hubUri() {
        try {
            return URI.create(properties.hubUrl());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Appium hub URL: " + properties.hubUrl(), e);
        }
    }

    private boolean hasLegacyFlatProps() {
        return hasText(properties.deviceName())
                || hasText(properties.appPath())
                || hasText(properties.appPackage())
                || hasText(properties.appActivity())
                || hasText(properties.bundleId())
                || hasText(properties.udid());
    }

    private String defaultAutomationName(String platformName) {
        String platform = firstText(platformName, properties.platformName());
        if ("iOS".equalsIgnoreCase(platform)) {
            return "XCUITest";
        }
        if ("Android".equalsIgnoreCase(platform)) {
            return "UiAutomator2";
        }
        return properties.automationName();
    }

    private boolean isAndroid(ResolvedAppiumDevice device) {
        return "Android".equalsIgnoreCase(device.platformName());
    }

    private boolean isIos(ResolvedAppiumDevice device) {
        return "iOS".equalsIgnoreCase(device.platformName());
    }

    private String firstText(String first, String fallback) {
        return hasText(first) ? first : fallback;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
