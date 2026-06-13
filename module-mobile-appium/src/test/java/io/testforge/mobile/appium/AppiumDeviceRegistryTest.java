package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppiumDeviceRegistryTest {

    @Test
    void legacyFlatPropsResolveIntoDevice() {
        ResolvedAppiumDevice device = new AppiumDeviceRegistry(new AppiumProperties(
                "http://localhost:4723",
                "Android",
                "emulator-5554",
                "/apps/demo.apk",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                null))
                .resolveDefault();

        assertThat(device.deviceId()).isEqualTo(AppiumDeviceRegistry.LEGACY_DEVICE_ID);
        assertThat(device.hubUrl()).isEqualTo(URI.create("http://localhost:4723"));
        assertThat(device.platformName()).isEqualTo("Android");
        assertThat(device.automationName()).isEqualTo("UiAutomator2");
        assertThat(device.appPath()).isEqualTo("/apps/demo.apk");
    }

    @Test
    void yamlMatrixResolvesSelectedDefaultDevice() {
        AppiumProperties.DeviceProperties android = new AppiumProperties.DeviceProperties(
                "Android",
                "Pixel 8",
                "storage:filename=demo.apk",
                null,
                null,
                null,
                "emulator-5554",
                "15",
                "UiAutomator2",
                Map.of("provider:options", Map.of("projectName", "TestForge")));

        ResolvedAppiumDevice device = new AppiumDeviceRegistry(properties(
                Map.of("remote-android", android),
                "remote-android"))
                .resolveDefault();

        assertThat(device.deviceId()).isEqualTo("remote-android");
        assertThat(device.appPath()).isEqualTo("storage:filename=demo.apk");
        assertThat(device.extraCapabilities())
                .containsEntry("provider:options", Map.of("projectName", "TestForge"));
    }

    @Test
    void unknownDeviceIdFailsClearly() {
        AppiumDeviceRegistry registry = new AppiumDeviceRegistry(properties(
                Map.of("android-local", androidDevice("/apps/demo.apk")),
                "android-local"));

        assertThatThrownBy(() -> registry.resolve("ios-local"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Appium device id 'ios-local'")
                .hasMessageContaining("android-local");
    }

    @Test
    void missingDefaultAndLegacyPropsFailClearly() {
        assertThatThrownBy(() -> new AppiumDeviceRegistry(properties(Map.of(), null)).resolveDefault())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No Appium default device configured");
    }

    @Test
    void unsupportedPlatformFailsClearly() {
        AppiumProperties.DeviceProperties device = new AppiumProperties.DeviceProperties(
                "Windows",
                "desktop",
                "/apps/demo.zip",
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of());

        assertThatThrownBy(() -> new AppiumDeviceRegistry(properties(Map.of("desktop", device), "desktop"))
                .resolveDefault())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported Appium platform 'Windows'");
    }

    @Test
    void androidRequiresAppOrPackageAndActivity() {
        AppiumProperties.DeviceProperties device = new AppiumProperties.DeviceProperties(
                "Android",
                "Pixel 8",
                null,
                "com.example",
                null,
                null,
                null,
                null,
                null,
                Map.of());

        assertThatThrownBy(() -> new AppiumDeviceRegistry(properties(Map.of("android", device), "android"))
                .resolveDefault())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required Android fields");
    }

    @Test
    void iosRequiresAppOrBundleId() {
        AppiumProperties.DeviceProperties device = new AppiumProperties.DeviceProperties(
                "iOS",
                "iPhone 15",
                null,
                null,
                null,
                null,
                null,
                null,
                "XCUITest",
                Map.of());

        assertThatThrownBy(() -> new AppiumDeviceRegistry(properties(Map.of("ios", device), "ios"))
                .resolveDefault())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing required iOS fields");
    }

    @Test
    void androidResolvesViaAppPackageAndActivityWithoutAppPath() {
        AppiumProperties.DeviceProperties device = new AppiumProperties.DeviceProperties(
                "Android",
                "Pixel 8",
                null,
                "com.example",
                ".MainActivity",
                null,
                null,
                null,
                "UiAutomator2",
                Map.of());

        ResolvedAppiumDevice resolved = new AppiumDeviceRegistry(properties(Map.of("android", device), "android"))
                .resolveDefault();

        assertThat(resolved.appPackage()).isEqualTo("com.example");
        assertThat(resolved.appActivity()).isEqualTo(".MainActivity");
    }

    @Test
    void iosResolvesViaBundleIdWithoutAppPath() {
        AppiumProperties.DeviceProperties device = new AppiumProperties.DeviceProperties(
                "iOS",
                "iPhone 15",
                null,
                null,
                null,
                "com.example.app",
                null,
                null,
                null,
                Map.of());

        ResolvedAppiumDevice resolved = new AppiumDeviceRegistry(properties(Map.of("ios", device), "ios"))
                .resolveDefault();

        assertThat(resolved.bundleId()).isEqualTo("com.example.app");
        // automation-name defaults to XCUITest for iOS when unset
        assertThat(resolved.automationName()).isEqualTo("XCUITest");
    }

    private AppiumProperties properties(Map<String, AppiumProperties.DeviceProperties> devices, String defaultDevice) {
        return new AppiumProperties(
                "http://localhost:4723",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                defaultDevice,
                null,
                null,
                devices,
                null);
    }

    private AppiumProperties.DeviceProperties androidDevice(String appPath) {
        return new AppiumProperties.DeviceProperties(
                "Android",
                "Pixel 8",
                appPath,
                null,
                null,
                null,
                null,
                null,
                "UiAutomator2",
                Map.of());
    }
}
