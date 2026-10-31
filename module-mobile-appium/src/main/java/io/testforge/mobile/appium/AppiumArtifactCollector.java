package io.testforge.mobile.appium;

import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.logging.LogEntry;

public class AppiumArtifactCollector {

    private static final String SOURCE = "module-mobile-appium";
    static final int DEVICE_LOG_LINES = 200;

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
        return capture(session, testName, null);
    }

    /**
     * @param testKey {@code <Class>-<method>}, the name the HTTP, SQL and Kafka
     *                reports of the same test carry, so the run summary groups them
     */
    public Optional<Path> capture(AppiumSession session, String testName, String testKey) {
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
        // session.md first: it needs nothing from a device that may have gone away
        RuntimeException failure = combine(
                writeSessionSummary(session, directory, testName),
                combine(captureScreenshot(session, directory),
                        combine(capturePageSource(session, directory), captureDeviceLog(session, directory))));
        // whatever was written is worth indexing, even if the other capture failed
        register(directory, "session.md", "text/markdown", testName, session.deviceId(), testKey);
        register(directory, "screenshot.png", "image/png", testName, session.deviceId(), testKey);
        register(directory, "page-source.xml", "application/xml", testName, session.deviceId(), testKey);
        register(directory, "device.log", "text/plain", testName, session.deviceId(), testKey);
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

    /** Which device, which session, which capabilities — secrets masked. */
    private RuntimeException writeSessionSummary(AppiumSession session, Path directory, String testName) {
        try {
            ResolvedAppiumDevice device = session.device();
            StringBuilder out = new StringBuilder("# Appium session — ").append(testName).append("\n\n")
                    .append("- device: ").append(device.deviceId()).append('\n')
                    .append("- platform: ").append(device.platformName())
                    .append(device.platformVersion() == null ? "" : " " + device.platformVersion()).append('\n')
                    .append("- device name: ").append(device.deviceName()).append('\n')
                    .append("- automation: ").append(device.automationName()).append('\n')
                    .append("- hub: ").append(CapabilityRedactor.hub(device.hubUrl().toString())).append('\n')
                    .append("- session id: ").append(sessionId(session)).append("\n\n")
                    .append("## Capabilities sent\n\n");
            CapabilityRedactor.redact(new AppiumCapabilitiesMapper().toCapabilities(device).asMap())
                    .forEach((key, value) -> out.append("- ").append(key).append(": ").append(value).append('\n'));
            Files.writeString(directory.resolve("session.md"), out.toString(), StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return new UncheckedIOException("Failed to write the Appium session summary in " + directory, e);
        } catch (RuntimeException e) {
            return new IllegalStateException("Failed to write the Appium session summary in " + directory, e);
        }
    }

    private static String sessionId(AppiumSession session) {
        try {
            Object id = session.driver().getSessionId();
            return id == null ? "—" : id.toString();
        } catch (RuntimeException e) {
            return "—";
        }
    }

    /**
     * The tail of {@code logcat} (Android) or {@code syslog} (iOS): the crash or
     * ANR behind a screen that froze is there, not in the screenshot. A driver
     * that offers neither writes nothing.
     */
    private RuntimeException captureDeviceLog(AppiumSession session, Path directory) {
        try {
            if (session.driver().manage() == null || session.driver().manage().logs() == null) {
                return null;
            }
            Set<String> types = session.driver().manage().logs().getAvailableLogTypes();
            String type = types.contains("logcat") ? "logcat" : types.contains("syslog") ? "syslog" : null;
            if (type == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            for (LogEntry entry : session.driver().manage().logs().get(type)) {
                lines.add(entry.getMessage());
            }
            List<String> tail = lines.subList(Math.max(0, lines.size() - DEVICE_LOG_LINES), lines.size());
            Files.writeString(directory.resolve("device.log"),
                    "# " + type + ", last " + tail.size() + " of " + lines.size() + " lines\n"
                            + String.join("\n", tail) + "\n",
                    StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return new UncheckedIOException("Failed to capture the device log in " + directory, e);
        } catch (RuntimeException e) {
            return new IllegalStateException("Failed to capture the device log in " + directory, e);
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

    private void register(Path directory, String file, String mediaType, String testName, String deviceId,
                          String testKey) {
        Path path = directory.resolve(file);
        if (Files.isRegularFile(path)) {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("test", String.valueOf(testName));
            metadata.put("device", String.valueOf(deviceId));
            if (testKey != null) {
                // the <Class>-<method> name the HTTP, SQL and Kafka reports of the same test carry
                metadata.put("testKey", testKey);
            }
            artifactSink.registerFile(SOURCE, "mobile-failure", directory.getFileName() + "/" + file, path, mediaType,
                    metadata);
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
