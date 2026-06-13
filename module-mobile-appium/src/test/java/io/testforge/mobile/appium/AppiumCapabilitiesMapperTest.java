package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.Platform;
import org.openqa.selenium.remote.DesiredCapabilities;

class AppiumCapabilitiesMapperTest {

    @Test
    void mapsYamlPropertiesToW3cAndAppiumCapabilities() {
        ResolvedAppiumDevice device = new ResolvedAppiumDevice(
                "remote-android",
                URI.create("http://localhost:4723"),
                "Android",
                "Pixel 8",
                "UiAutomator2",
                "storage:filename=demo.apk",
                "com.example",
                ".MainActivity",
                null,
                "device-1",
                "15",
                Map.of(
                        "provider:options", Map.of("projectName", "TestForge"),
                        "customFlag", true));

        DesiredCapabilities capabilities = new AppiumCapabilitiesMapper().toCapabilities(device);

        assertThat(capabilities.asMap())
                .containsEntry("platformName", Platform.ANDROID)
                .containsEntry("appium:deviceName", "Pixel 8")
                .containsEntry("appium:automationName", "UiAutomator2")
                .containsEntry("appium:app", "storage:filename=demo.apk")
                .containsEntry("appium:appPackage", "com.example")
                .containsEntry("appium:appActivity", ".MainActivity")
                .containsEntry("appium:platformVersion", "15")
                .containsEntry("appium:udid", "device-1")
                .containsEntry("customFlag", true);
        assertThat(capabilities.getCapability("provider:options"))
                .isEqualTo(Map.of("projectName", "TestForge"));
    }

    @Test
    void extraCapabilitiesOverrideMappedOnes() {
        ResolvedAppiumDevice device = new ResolvedAppiumDevice(
                "android-local",
                URI.create("http://localhost:4723"),
                "Android",
                "Pixel 8",
                "UiAutomator2",
                "/apps/demo.apk",
                null,
                null,
                null,
                null,
                "15",
                Map.of("appium:platformVersion", "16"));

        DesiredCapabilities capabilities = new AppiumCapabilitiesMapper().toCapabilities(device);

        // extra-capabilities are applied last, so they win over mapped values
        assertThat(capabilities.getCapability("appium:platformVersion")).isEqualTo("16");
    }
}
