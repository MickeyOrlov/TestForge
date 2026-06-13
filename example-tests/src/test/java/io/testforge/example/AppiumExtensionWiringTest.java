package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.appium.java_client.AppiumDriver;
import io.testforge.mobile.appium.AppiumArtifactCollector;
import io.testforge.mobile.appium.AppiumDriverFactory;
import io.testforge.mobile.appium.AppiumExtension;
import io.testforge.mobile.appium.AppiumProperties;
import io.testforge.mobile.appium.AppiumSession;
import io.testforge.mobile.appium.MobileDevice;
import io.testforge.mobile.appium.ResolvedAppiumDevice;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@SpringBootTest(properties = {
        "forge.mobile.appium.enabled=true",
        "forge.mobile.appium.artifacts-dir=build/appium-artifacts/example",
        "forge.mobile.appium.devices.android-local.platform-name=Android",
        "forge.mobile.appium.devices.android-local.device-name=Pixel 8",
        "forge.mobile.appium.devices.android-local.app-path=/apps/demo.apk"
})
@ExtendWith(AppiumExtension.class)
class AppiumExtensionWiringTest {

    @Test
    void injectsAppiumSession(@MobileDevice("android-local") AppiumSession session) {
        assertThat(session.deviceId()).isEqualTo("android-local");
        assertThat(session.device().platformName()).isEqualTo("Android");
    }

    @Test
    void injectsAppiumDriver(@MobileDevice("android-local") AppiumDriver driver) {
        assertThat(driver).isNotNull();
    }

    @TestConfiguration
    static class FakeAppiumConfig {

        @Bean
        @Primary
        AppiumDriverFactory fakeAppiumDriverFactory(AppiumProperties properties) {
            return new AppiumDriverFactory(properties) {
                @Override
                public AppiumSession startSession(String deviceId) {
                    ResolvedAppiumDevice device = new ResolvedAppiumDevice(
                            deviceId,
                            URI.create(properties.hubUrl()),
                            "Android",
                            "Pixel 8",
                            "UiAutomator2",
                            "/apps/demo.apk",
                            null,
                            null,
                            null,
                            null,
                            null,
                            Map.of());
                    return new AppiumSession(
                            mock(AppiumDriver.class),
                            device,
                            Path.of(properties.artifactsDir()),
                            new AppiumArtifactCollector(properties));
                }
            };
        }
    }
}
