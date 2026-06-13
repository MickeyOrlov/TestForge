package io.testforge.mobile.appium;

import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.SmartLifecycle;

public class AppiumNodeManager implements SmartLifecycle {

    private final AppiumProperties properties;
    private final ProcessLauncher launcher;
    private final AppiumStatusProbe statusProbe;
    private volatile Process process;

    public AppiumNodeManager(
            AppiumProperties properties,
            ProcessLauncher launcher,
            AppiumStatusProbe statusProbe) {
        this.properties = properties;
        this.launcher = launcher;
        this.statusProbe = statusProbe;
    }

    @Override
    public void start() {
        if (!properties.node().autoStart() || isRunning()) {
            return;
        }
        List<String> command = command();
        try {
            process = launcher.launch(command);
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
            throw new IllegalArgumentException("Invalid Appium hub URL: " + properties.hubUrl(), e);
        }
        Waiter waiter = new Waiter(new WaitProperties(
                properties.node().startupTimeout(),
                pollInterval(properties.node().startupTimeout())));
        waiter.awaitTrue(
                "Appium node ready at " + hubUrl.resolve(properties.node().statusPath()),
                () -> statusProbe.isReady(hubUrl, properties.node().statusPath()));
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
