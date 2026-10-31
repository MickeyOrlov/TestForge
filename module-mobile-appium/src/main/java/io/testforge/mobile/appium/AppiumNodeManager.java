package io.testforge.mobile.appium;

import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.SmartLifecycle;

public class AppiumNodeManager implements SmartLifecycle {

    static final String LOG_FILE = "appium-node.log";
    static final int LOG_TAIL_LINES = 20;
    private static final String SOURCE = "module-mobile-appium";

    private final AppiumProperties properties;
    private final ProcessLauncher launcher;
    private final AppiumStatusProbe statusProbe;
    private final ArtifactSink artifactSink;
    private volatile Process process;

    public AppiumNodeManager(
            AppiumProperties properties,
            ProcessLauncher launcher,
            AppiumStatusProbe statusProbe) {
        this(properties, launcher, statusProbe, ArtifactSink.NO_OP);
    }

    public AppiumNodeManager(
            AppiumProperties properties,
            ProcessLauncher launcher,
            AppiumStatusProbe statusProbe,
            ArtifactSink artifactSink) {
        this.properties = properties;
        this.launcher = launcher;
        this.statusProbe = statusProbe;
        this.artifactSink = artifactSink == null ? ArtifactSink.NO_OP : artifactSink;
    }

    /** Where the node's own output goes: next to the failure artifacts, uploaded with them. */
    public Path logFile() {
        return Path.of(properties.artifactsDir(), LOG_FILE);
    }

    @Override
    public void start() {
        if (!properties.node().autoStart() || isRunning()) {
            return;
        }
        List<String> command = command();
        try {
            process = launcher.launch(command, logFile());
            waitUntilReady();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start local Appium node with command " + command, e);
        } catch (RuntimeException e) {
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        Process current = process;
        process = null;
        if (current != null && current.isAlive()) {
            current.destroy();
        }
        if (current != null && Files.isRegularFile(logFile())) {
            artifactSink.registerFile(SOURCE, "appium-node", LOG_FILE, logFile(), "text/plain", Map.of());
        }
    }

    @Override
    public boolean isRunning() {
        Process current = process;
        return current != null && current.isAlive();
    }

    @Override
    public boolean isAutoStartup() {
        return properties.node().autoStart();
    }

    List<String> command() {
        List<String> command = new ArrayList<>();
        command.add(properties.node().command());
        command.addAll(properties.node().args());
        return List.copyOf(command);
    }

    private void waitUntilReady() {
        URI hubUrl;
        try {
            hubUrl = URI.create(properties.hubUrl());
        } catch (IllegalArgumentException e) {
            throw CapabilityRedactor.invalidHub(e);
        }
        Waiter waiter = new Waiter(new WaitProperties(
                properties.node().startupTimeout(),
                pollInterval(properties.node().startupTimeout())));
        String target = CapabilityRedactor.hub(hubUrl.resolve(properties.node().statusPath()).toString());
        // ready, or dead: a node that exited will never answer, so waiting out the timeout only hides why
        try {
            waiter.awaitTrue("Appium node ready at " + target, () -> !isAlive()
                    || statusProbe.isReady(hubUrl, properties.node().statusPath()));
        } catch (RuntimeException timeout) {
            throw new IllegalStateException("The local Appium node did not answer " + target + " within "
                    + properties.node().startupTimeout() + logTail(), timeout);
        }
        if (!isAlive()) {
            throw new IllegalStateException("The local Appium node exited with code " + exitCode()
                    + " before it answered " + target + logTail());
        }
    }

    private boolean isAlive() {
        Process current = process;
        return current != null && current.isAlive();
    }

    private String exitCode() {
        try {
            Process current = process;
            return current == null ? "unknown" : String.valueOf(current.exitValue());
        } catch (IllegalThreadStateException e) {
            return "unknown";
        }
    }

    /** The end of the node's own log, where "address already in use" or a missing driver is. */
    String logTail() {
        Path log = logFile();
        if (!Files.isRegularFile(log)) {
            return ". No output was captured (" + log + " does not exist).";
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            List<String> tail = lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size());
            return ". Last " + tail.size() + " line(s) of " + log + ":" + System.lineSeparator()
                    + String.join(System.lineSeparator(), tail);
        } catch (IOException | java.io.UncheckedIOException e) {
            return ". Its log " + log + " could not be read: " + e.getMessage();
        }
    }

    private Duration pollInterval(Duration timeout) {
        Duration defaultInterval = Duration.ofMillis(500);
        if (timeout.compareTo(defaultInterval) > 0) {
            return defaultInterval;
        }
        return timeout.dividedBy(2).compareTo(Duration.ofMillis(1)) < 0
                ? Duration.ofMillis(1)
                : timeout.dividedBy(2);
    }
}
