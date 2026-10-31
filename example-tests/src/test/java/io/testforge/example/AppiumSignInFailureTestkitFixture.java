package io.testforge.example;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.openqa.selenium.OutputType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Fails on purpose in {@code @BeforeEach}; run only through {@code AppiumLifecycleFailureExampleTest}. */
@SpringBootTest(properties = {
        "forge.mobile.appium.enabled=true",
        "forge.mobile.appium.artifacts-dir=build/appium-artifacts/lifecycle",
        "forge.mobile.appium.devices.android-local.platform-name=Android",
        "forge.mobile.appium.devices.android-local.device-name=Pixel 8",
        "forge.mobile.appium.devices.android-local.app-path=/apps/demo.apk"
})
@ExtendWith(AppiumExtension.class)
class AppiumSignInFailureTestkitFixture {

    @BeforeEach
    void signIn(@MobileDevice("android-local") AppiumSession session) {
        throw new AssertionError("the sign-in screen never accepted the password");
    }

    @Test
    void opensTheDashboard(@MobileDevice("android-local") AppiumSession session) {
    }

    @TestConfiguration
    static class FakeDevice {

        @Bean
        @Primary
        AppiumDriverFactory fakeAppiumDriverFactory(AppiumProperties properties) {
            return new AppiumDriverFactory(properties) {
                @Override
                public AppiumSession startSession(String deviceId) {
                    AppiumDriver driver = mock(AppiumDriver.class);
                    when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1});
                    when(driver.getPageSource()).thenReturn("<hierarchy><node text='Wrong password'/></hierarchy>");
                    ResolvedAppiumDevice device = new ResolvedAppiumDevice(deviceId, URI.create(properties.hubUrl()),
                            "Android", "Pixel 8", "UiAutomator2", "/apps/demo.apk", null, null, null, null, null,
                            Map.of());
                    return new AppiumSession(driver, device, Path.of(properties.artifactsDir()),
                            new AppiumArtifactCollector(properties));
                }
            };
        }
    }
}
