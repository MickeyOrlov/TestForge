package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppiumNodeManagerTest {

    @Test
    void doesNotStartProcessWhenAutoStartIsFalse() {
        RecordingLauncher launcher = new RecordingLauncher();
        AppiumNodeManager manager = new AppiumNodeManager(properties(false), launcher, (hub, path) -> true);

        manager.start();

        assertThat(launcher.commands).isEmpty();
    }

    @Test
    void startsConfiguredCommandAndWaitsForStatus() {
        RecordingLauncher launcher = new RecordingLauncher();
        List<URI> probed = new ArrayList<>();
        AppiumNodeManager manager = new AppiumNodeManager(properties(true), launcher, (hub, path) -> {
            probed.add(hub.resolve(path));
            return true;
        });

        manager.start();

        assertThat(launcher.commands).containsExactly(List.of("appium", "--base-path", "/"));
        assertThat(probed).containsExactly(URI.create("http://localhost:4723/status"));
        assertThat(manager.isRunning()).isTrue();
        manager.stop();
        assertThat(manager.isRunning()).isFalse();
    }

    private AppiumProperties properties(boolean autoStart) {
        return new AppiumProperties(
                "http://localhost:4723",
                "Android",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Map.of(),
                new AppiumProperties.NodeProperties(
                        autoStart,
                        "appium",
                        List.of("--base-path", "/"),
                        Duration.ofMillis(50),
                        "/status"));
    }

    private static final class RecordingLauncher implements ProcessLauncher {

        final List<List<String>> commands = new ArrayList<>();
        final FakeProcess process = new FakeProcess();

        @Override
        public Process launch(List<String> command) {
            commands.add(command);
            process.alive = true;
            return process;
        }
    }

    private static final class FakeProcess extends Process {

        boolean alive;

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public int exitValue() {
            return alive ? 1 : 0;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
