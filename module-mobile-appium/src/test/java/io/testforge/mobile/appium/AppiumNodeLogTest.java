package io.testforge.mobile.appium;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppiumNodeLogTest {

    @TempDir
    Path temp;

    @Test
    void aChattyProcessNeverBlocksBecauseItsOutputGoesToAFile() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/sh")), "needs a POSIX shell");
        Path log = temp.resolve("out/node.log");

        // far more than a pipe buffer holds: with an unread pipe this would block
        Process process = new DefaultProcessLauncher().launch(
                List.of("/bin/sh", "-c", "i=0; while [ $i -lt 20000 ]; do echo log line $i; i=$((i+1)); done; echo ready"),
                log);

        assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("the process finished").isTrue();
        assertThat(Files.readString(log)).contains("log line 19999").endsWith("ready\n");
    }

    @Test
    void aNodeThatExitsIsReportedAtOnceWithTheEndOfItsLog() {
        DeadOnArrival launcher = new DeadOnArrival("Error: listen EADDRINUSE: address already in use 0.0.0.0:4723");
        AppiumNodeManager manager = new AppiumNodeManager(properties(Duration.ofSeconds(30)), launcher,
                (hub, path) -> false);

        long started = System.nanoTime();
        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exited with code 1 before it answered http://localhost:4723/status")
                .hasMessageContaining("EADDRINUSE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void aNodeThatNeverAnswersTimesOutWithItsLog() {
        Alive launcher = new Alive("[Appium] Could not find a driver for automationName 'UiAutomator2'");
        AppiumNodeManager manager = new AppiumNodeManager(properties(Duration.ofMillis(100)), launcher,
                (hub, path) -> false);

        assertThatThrownBy(manager::start)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not answer http://localhost:4723/status within PT0.1S")
                .hasMessageContaining("Could not find a driver");
        assertThat(launcher.process.isAlive()).as("the node is stopped again").isFalse();
    }

    @Test
    void theNodeLogJoinsTheRunIndexWhenTheNodeStops() {
        Alive launcher = new Alive("[Appium] Welcome to Appium");
        RecordingArtifactSink sink = new RecordingArtifactSink();
        AppiumNodeManager manager = new AppiumNodeManager(properties(Duration.ofSeconds(5)), launcher,
                (hub, path) -> true, sink);

        manager.start();
        manager.stop();

        assertThat(sink.registered).singleElement().satisfies(artifact -> {
            assertThat(artifact.name()).isEqualTo("appium-node.log");
            assertThat(artifact.file()).isEqualTo(temp.resolve("appium-node.log"));
        });
    }

    private AppiumProperties properties(Duration timeout) {
        return new AppiumProperties("http://localhost:4723", "Android", null, null, null, null, null, null, null,
                null, null, null, temp.toString(), Map.of(),
                new AppiumProperties.NodeProperties(true, "appium", List.of(), timeout, "/status"));
    }

    /** Writes the given line to the log, then stays alive until destroyed. */
    private static class Alive implements ProcessLauncher {
        final String line;
        final FakeProcess process = new FakeProcess(true);

        Alive(String line) {
            this.line = line;
        }

        @Override
        public Process launch(List<String> command) {
            throw new AssertionError("the node must be launched with a log file");
        }

        @Override
        public Process launch(List<String> command, Path log) throws IOException {
            Files.createDirectories(log.getParent());
            Files.writeString(log, "[Appium] starting\n" + line + "\n");
            return process;
        }
    }

    /** Writes its last words to the log and is already dead. */
    private static final class DeadOnArrival extends Alive {
        DeadOnArrival(String line) {
            super(line);
            process.alive = false;
        }
    }

    private static final class FakeProcess extends Process {
        boolean alive;

        FakeProcess(boolean alive) {
            this.alive = alive;
        }

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
            return 1;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("running");
            }
            return 1;
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
