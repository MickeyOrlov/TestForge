package io.testforge.load;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Drains output while running, retains at most 8 KiB, kills the process tree on timeout/interruption. */
public final class LocalLoadProcess implements LoadProcess {
    static final int OUTPUT_LIMIT = 8192;

    @Override
    public Result run(List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile())
                .redirectErrorStream(true);
        // Do not inherit CI credentials or ambient K6_* flags/outputs. Explicit credentials are opt-in.
        Map<String, String> inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String key : List.of("PATH", "HOME", "TMPDIR", "TMP", "TEMP", "SystemRoot", "WINDIR",
                "SSL_CERT_FILE", "SSL_CERT_DIR")) {
            if (inherited.containsKey(key)) {
                builder.environment().put(key, inherited.get(key));
            }
        }
        builder.environment().putAll(environment);
        Process process = builder.start();
        Thread shutdown = new Thread(() -> stop(process), "testforge-k6-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdown);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread drain = Thread.ofVirtual().start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] bytes = new byte[4096];
                int read;
                while ((read = stream.read(bytes)) != -1) {
                    synchronized (captured) {
                        captured.write(bytes, 0, Math.min(read, OUTPUT_LIMIT - captured.size()));
                    }
                }
            } catch (IOException ignored) {
                // Stopping a timed-out process closes its pipe; output is diagnostic only.
            }
        });
        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                stop(process);
            }
            drain.join(1000);
            synchronized (captured) {
                return new Result(finished ? process.exitValue() : -1, !finished,
                        captured.toString(StandardCharsets.UTF_8));
            }
        } finally {
            if (process.isAlive()) {
                stop(process);
            }
            try {
                process.getInputStream().close();
                process.getOutputStream().close();
            } catch (IOException ignored) {
                // Pipe cleanup must not replace an interruption or a completed process outcome.
            }
            drain.interrupt();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has begun; the hook owns the remaining process cleanup.
            }
        }
    }

    private static void stop(Process process) {
        // Snapshot children before terminating the parent, which could otherwise orphan them.
        List<ProcessHandle> children = process.descendants().toList();
        children.reversed().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
