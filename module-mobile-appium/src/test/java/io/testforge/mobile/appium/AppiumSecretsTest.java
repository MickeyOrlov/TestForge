package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.appium.java_client.AppiumDriver;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.openqa.selenium.SessionNotCreatedException;
import org.openqa.selenium.remote.DesiredCapabilities;

class AppiumSecretsTest {

    private static final String HUB = "https://ci-user:s3cretKey42@hub.cloud.example/wd/hub";

    @Test
    void aSessionThatCannotStartIsReportedWithoutItsSecrets() {
        ResolvedAppiumDevice device = new ResolvedAppiumDevice("cloud-pixel", URI.create(HUB), "Android",
                "Google Pixel 8", "UiAutomator2", "storage:filename=demo.apk", null, null, null, null, "15",
                Map.of("provider:options", Map.of("accessKey", "AK-7731-secret", "projectName", "TestForge")));
        AppiumDriverFactory factory = new AppiumDriverFactory(null, new AppiumCapabilitiesMapper(), null, Path.of("x")) {
            @Override
            protected AppiumDriver newDriver(ResolvedAppiumDevice resolved, DesiredCapabilities capabilities) {
                throw new SessionNotCreatedException("Could not start a new session. Response code 401. "
                        + "Message: invalid accessKey AK-7731-secret for ci-user\nHost info: ...\n"
                        + "Capabilities {provider:options: {accessKey: AK-7731-secret}}");
            }
        };

        assertThatThrownBy(() -> factory.createDriver(device))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not start an Appium session on device 'cloud-pixel'")
                .hasMessageContaining("at https://hub.cloud.example/wd/hub")
                .hasMessageContaining("SessionNotCreatedException")
                .hasMessageContaining("invalid accessKey *** for ***")
                .hasMessageNotContaining("AK-7731-secret")
                .hasMessageNotContaining("s3cretKey42")
                .hasNoCause();
    }

    @Test
    void anInvalidHubUrlIsNotEchoed() {
        AppiumProperties properties = new AppiumProperties("https://ci-user:s3cret Key@hub.example", "Android",
                "Pixel", "/a.apk", null, null, null, null, null, null, null, null, null, Map.of(), null);

        assertThatThrownBy(() -> new AppiumDeviceRegistry(properties).resolveDefault())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Invalid Appium hub URL (")
                .hasMessageNotContaining("s3cret")
                .hasNoCause();
    }

    @Test
    void secretsAreCollectedAtAnyDepthAndFromTheHubUserInfo() {
        assertThat(CapabilityRedactor.secrets(Map.of(
                        "appium:app", "/a.apk",
                        "provider:options", Map.of("accessKey", "AK1", "nested", Map.of("apiToken", "T1"))),
                HUB)).containsExactlyInAnyOrder("AK1", "T1", "ci-user:s3cretKey42", "ci-user", "s3cretKey42");
    }
}
