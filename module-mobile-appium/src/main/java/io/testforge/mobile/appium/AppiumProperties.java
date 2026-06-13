package io.testforge.mobile.appium;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.mobile.appium")
public record AppiumProperties(
        String hubUrl,
        String platformName,
        String deviceName,
        String appPath,
        String appPackage,
        String appActivity,
        String bundleId,
        String udid,
        String platformVersion,
        String automationName,
        String defaultDevice,
        Boolean artifactsOnFailure,
        String artifactsDir,
        Map<String, DeviceProperties> devices,
        NodeProperties node) {

    public AppiumProperties {
        if (hubUrl == null || hubUrl.isBlank()) {
            hubUrl = "http://localhost:4723";
        }
        if (platformName == null || platformName.isBlank()) {
            platformName = "Android";
        }
        if (automationName == null || automationName.isBlank()) {
            automationName = "UiAutomator2";
        }
        if (artifactsOnFailure == null) {
            artifactsOnFailure = true;
        }
        if (artifactsDir == null || artifactsDir.isBlank()) {
            artifactsDir = "build/appium-artifacts";
        }
        devices = Map.copyOf(devices == null ? Map.of() : devices);
        if (node == null) {
            node = new NodeProperties(null, null, null, null, null);
        }
    }

    public record DeviceProperties(
            String platformName,
            String deviceName,
            String appPath,
            String appPackage,
            String appActivity,
            String bundleId,
            String udid,
            String platformVersion,
            String automationName,
            Map<String, Object> extraCapabilities) {

        public DeviceProperties {
            extraCapabilities = Map.copyOf(extraCapabilities == null ? Map.of() : extraCapabilities);
        }
    }

    public record NodeProperties(
            Boolean autoStart,
            String command,
            List<String> args,
            Duration startupTimeout,
            String statusPath) {

        public NodeProperties {
            if (autoStart == null) {
                autoStart = false;
            }
            if (command == null || command.isBlank()) {
                command = "appium";
            }
            args = List.copyOf(args == null ? List.of() : args);
            if (startupTimeout == null) {
                startupTimeout = Duration.ofSeconds(30);
            }
            if (statusPath == null || statusPath.isBlank()) {
                statusPath = "/status";
            }
        }
    }
}
