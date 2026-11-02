package io.testforge.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLoadProcessTest {
    @TempDir Path dir;

    private List<String> command(String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                Path.of(ProcessFixture.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                        + java.io.File.pathSeparator
                        + Path.of(LocalLoadProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                ProcessFixture.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    @Test void verboseChildDoesNotBlockOrGrowTheOutputBuffer() throws Exception {
        LoadProcess.Result result = new LocalLoadProcess().run(command("noise"), dir, Map.of(), Duration.ofSeconds(10));
        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.output()).hasSize(LocalLoadProcess.OUTPUT_LIMIT);
    }

    @Test void onlyExplicitCredentialsReachTheChild() throws Exception {
        LoadProcess.Result result = new LocalLoadProcess().run(command("env"), dir,
                Map.of("LOAD_TOKEN", "explicit"), Duration.ofSeconds(10));
        assertThat(result.output()).isEqualTo("explicit:null:null");
    }

    @Test void timeoutStopsTheParentAndItsChild() throws Exception {
        Path parent = dir.resolve("parent.pid");
        Path child = dir.resolve("child.pid");
        LoadProcess.Result result = new LocalLoadProcess().run(command("parent", parent.toString(), child.toString()),
                dir, Map.of(), Duration.ofSeconds(2));
        assertThat(result.timedOut()).isTrue();
        assertThat(child).exists();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(alive(parent)).isFalse();
            assertThat(alive(child)).isFalse();
        });
    }

    @Test void interruptionStopsARealRunningChild() throws Exception {
        Path pid = dir.resolve("cancel.pid");
        List<String> command = command("wait", pid.toString());
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = Thread.ofVirtual().start(() -> {
            try {
                new LocalLoadProcess().run(command, dir, Map.of(), Duration.ofSeconds(30));
            } catch (InterruptedException expected) {
                interrupted.set(true);
            } catch (java.io.IOException e) {
                throw new AssertionError(e);
            }
        });
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.isRegularFile(pid));
            worker.interrupt();
            worker.join(5000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(interrupted).isTrue();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(alive(pid)).isFalse());
        } finally {
            worker.interrupt();
        }
    }

    private boolean alive(Path pid) throws Exception {
        return ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test void terminatingTheOwningJvmAlsoStopsItsChild() throws Exception {
        Path parent = dir.resolve("supervisor.pid");
        Path child = dir.resolve("supervised.pid");
        Process supervisor = new ProcessBuilder(command("supervisor", parent.toString(), child.toString())).start();
        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> Files.isRegularFile(child));
            supervisor.destroy();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(alive(child)).isFalse());
        } finally {
            supervisor.destroyForcibly();
            if (Files.isRegularFile(child)) {
                ProcessHandle.of(Long.parseLong(Files.readString(child))).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }
}
