package io.testforge.mobile.appium;

import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;

public class AppiumArtifactCollector {

    private static final String SOURCE = "module-mobile-appium";

    private final boolean enabled;
    private final ArtifactSink artifactSink;

    public AppiumArtifactCollector(AppiumProperties properties) {
        this(properties, ArtifactSink.NO_OP);
    }

    public AppiumArtifactCollector(AppiumProperties properties, ArtifactSink artifactSink) {
        this.enabled = properties.artifactsOnFailure();
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    public Optional<Path> capture(AppiumSession session, String testName) {
        if (!enabled) {
            return Optional.empty();
        }

        Path directory = artifactDirectory(session.artifactsDir(), testName, session.deviceId());
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create Appium artifacts directory " + directory, e);
        }

        // Capture each artifact independently: a screenshot failure must not
        // prevent the page source (and vice versa) — both are diagnostics for
        // an already-failing test. Failures are collected and surfaced as one
        // exception so the caller can attach it as suppressed.
        RuntimeException failure = combine(
                captureScreenshot(session, directory),
                capturePageSource(session, directory));
        // whatever was written is worth indexing, even if the other capture failed
        register(directory, "screenshot.png", "image/png", testName, session.deviceId());
        register(directory, "page-source.xml", "application/xml", testName, session.deviceId());
        if (failure != null) {
            throw failure;
        }
        return Optional.of(directory);
    }

    private RuntimeException captureScreenshot(AppiumSession session, Path directory) {
        if (!(session.driver() instanceof TakesScreenshot screenshotDriver)) {
            return null;
        }
        try {
            byte[] screenshot = screenshotDriver.getScreenshotAs(OutputType.BYTES);
            Files.write(directory.resolve("screenshot.png"), screenshot);
            return null;
        } catch (IOException e) {
            return new UncheckedIOException("Failed to capture Appium screenshot in " + directory, e);
        } catch (RuntimeException e) {
            return new IllegalStateException("Failed to capture Appium screenshot in " + directory, e);
        }
    }

    private RuntimeException capturePageSource(AppiumSession session, Path directory) {
        try {
            Files.writeString(
                    directory.resolve("page-source.xml"),
                    session.driver().getPageSource(),
                    StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return new UncheckedIOException("Failed to capture Appium page source in " + directory, e);
        } catch (RuntimeException e) {
            return new IllegalStateException("Failed to capture Appium page source in " + directory, e);
        }
    }

    private void register(Path directory, String file, String mediaType, String testName, String deviceId) {
        Path path = directory.resolve(file);
        if (Files.isRegularFile(path)) {
            artifactSink.registerFile(SOURCE, "mobile-failure", directory.getFileName() + "/" + file, path, mediaType,
                    Map.of("test", String.valueOf(testName), "device", String.valueOf(deviceId)));
        }
    }

    private RuntimeException combine(RuntimeException first, RuntimeException second) {
        if (first == null) {
            return second;
        }
        if (second != null) {
            first.addSuppressed(second);
        }
        return first;
    }

    public Path artifactDirectory(Path root, String testName, String deviceId) {
        return root.resolve(safe("%s_%s".formatted(testName, deviceId)));
    }

    private String safe(String value) {
        String safe = value == null ? "test" : value
                .replaceAll("[^a-zA-Z0-9._-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("(^_+|_+$)", "");
        return safe.isBlank() ? "test" : safe;
    }
}
