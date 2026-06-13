package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Acceptance for AppiumExtension lifecycle, with a fake factory instead of a
 * device farm: one session per device is reused across parameters, and the
 * session is closed (driver quit) after the test.
 */
@SpringBootTest(properties = {
        "forge.mobile.appium.enabled=true",
        "forge.mobile.appium.artifacts-dir=build/appium-artifacts/behavior",
        "forge.mobile.appium.devices.android-local.platform-name=Android",
        "forge.mobile.appium.devices.android-local.device-name=Pixel 8",
        "forge.mobile.appium.devices.android-local.app-path=/apps/demo.apk"
})
@ExtendWith(AppiumExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppiumExtensionBehaviorIT {

    static final AtomicInteger SESSIONS_OPENED = new AtomicInteger();
    static AppiumDriver lastDriver;

    @Test
    @Order(1)
    void reusesOneSessionForSessionAndDriverOfSameDevice(
            @MobileDevice("android-local") AppiumSession session,
            @MobileDevice("android-local") AppiumDriver driver) {
        // both parameters resolve to the same session, opened once
        assertThat(session.driver()).isSameAs(driver);
        assertThat(SESSIONS_OPENED.get()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void previousSessionWasClosedAfterTest() {
        // afterEach of the @Order(1) test closed its session -> driver.quit()
        verify(lastDriver).quit();
    }

    @TestConfiguration
    static class FakeAppiumConfig {

        @Bean
        @Primary
        AppiumDriverFactory fakeAppiumDriverFactory(AppiumProperties properties) {
            return new AppiumDriverFactory(properties) {
                @Override
                public AppiumSession startSession(String deviceId) {
                    SESSIONS_OPENED.incrementAndGet();
                    AppiumDriver driver = mock(AppiumDriver.class);
                    lastDriver = driver;
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
                            driver,
                            device,
                            Path.of(properties.artifactsDir()),
                            new AppiumArtifactCollector(properties));
                }
            };
        }
    }
}
