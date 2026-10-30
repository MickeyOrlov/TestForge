package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.appium.java_client.AppiumDriver;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebDriverException;

class AppiumArtifactCollectorTest {

    @TempDir
    Path temp;

    @Test
    void capturesScreenshotAndPageSourceIntoSanitizedDirectory() throws Exception {
        AppiumDriver driver = mock(AppiumDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1, 2, 3});
        when(driver.getPageSource()).thenReturn("<hierarchy/>");
        AppiumSession session = session(driver, enabledCollector(true));

        Path directory = session.captureFailureArtifacts("Login Mobile IT logsIn()").orElseThrow();

        assertThat(directory.getFileName().toString()).isEqualTo("Login_Mobile_IT_logsIn_android-local");
        assertThat(Files.readAllBytes(directory.resolve("screenshot.png"))).containsExactly(1, 2, 3);
        assertThat(Files.readString(directory.resolve("page-source.xml"))).isEqualTo("<hierarchy/>");
    }

    @Test
    void pageSourceIsStillCapturedWhenScreenshotFails() throws Exception {
        AppiumDriver driver = mock(AppiumDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenThrow(new WebDriverException("no screen"));
        when(driver.getPageSource()).thenReturn("<hierarchy/>");
        AppiumSession session = session(driver, enabledCollector(true));

        // capture still throws (surfaced as suppressed by the extension)...
        assertThatThrownBy(() -> session.captureFailureArtifacts("FailIT_shows"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("screenshot");

        // ...but the page source was written despite the screenshot failure
        Path directory = temp.resolve("FailIT_shows_android-local");
        assertThat(Files.readString(directory.resolve("page-source.xml"))).isEqualTo("<hierarchy/>");
        assertThat(directory.resolve("screenshot.png")).doesNotExist();
    }

    @Test
    void doesNothingWhenArtifactsAreDisabled() {
        AppiumDriver driver = mock(AppiumDriver.class);
        AppiumSession session = session(driver, enabledCollector(false));

        assertThat(session.captureFailureArtifacts("LoginMobileIT_logsIn")).isEmpty();

        verifyNoInteractions(driver);
    }

    @Test
    void closesDriver() {
        AppiumDriver driver = mock(AppiumDriver.class);
        session(driver, enabledCollector(false)).close();

        verify(driver).quit();
    }

    private AppiumSession session(AppiumDriver driver, AppiumArtifactCollector collector) {
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
                null,
                Map.of());
        return new AppiumSession(driver, device, temp, collector);
    }

    private final RecordingArtifactSink sink = new RecordingArtifactSink();

    private AppiumArtifactCollector enabledCollector(boolean enabled) {
        return new AppiumArtifactCollector(new AppiumProperties(
                "http://localhost:4723",
                "Android",
                "Pixel 8",
                "/apps/demo.apk",
                null,
                null,
                null,
                null,
                null,
                "UiAutomator2",
                null,
                enabled,
                temp.toString(),
                Map.of(),
                null), sink);
    }

    @Test
    void capturedFilesAreRegisteredInTheRunIndex() {
        AppiumDriver driver = mock(AppiumDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenReturn(new byte[]{1});
        when(driver.getPageSource()).thenReturn("<hierarchy/>");

        session(driver, enabledCollector(true)).captureFailureArtifacts("LoginIT logsIn").orElseThrow();

        assertThat(sink.registered).extracting(artifact -> artifact.name()).containsExactly(
                "LoginIT_logsIn_android-local/screenshot.png", "LoginIT_logsIn_android-local/page-source.xml");
        assertThat(sink.registered.getFirst().metadata())
                .containsEntry("test", "LoginIT logsIn")
                .containsEntry("device", "android-local");
    }

    @Test
    void aPartialCaptureStillRegistersWhatWasWritten() {
        AppiumDriver driver = mock(AppiumDriver.class);
        when(driver.getScreenshotAs(OutputType.BYTES)).thenThrow(new IllegalStateException("no screen"));
        when(driver.getPageSource()).thenReturn("<hierarchy/>");

        assertThatThrownBy(() -> session(driver, enabledCollector(true)).captureFailureArtifacts("t"))
                .hasMessageContaining("screenshot");
        assertThat(sink.registered).extracting(artifact -> artifact.name())
                .containsExactly("t_android-local/page-source.xml");
    }
}
