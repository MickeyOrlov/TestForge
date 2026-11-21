package io.testforge.load;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;
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
        Map<String, String> inherited = inheritedEnvironment(builder.environment(),
                System.getProperty("os.name").startsWith("Windows"));
        builder.environment().clear();
        builder.environment().putAll(inherited);
        builder.environment().putAll(environment);
        Process process = builder.start();
        Map<Long, ProcessHandle> children = new ConcurrentHashMap<>();
        Thread shutdown = new Thread(() -> stop(process, children), "testforge-k6-shutdown");
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
            long started = System.nanoTime();
            boolean finished;
            do {
                rememberChildren(process, children);
                long remaining = timeout.toNanos() - (System.nanoTime() - started);
                finished = process.waitFor(Math.max(0, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50))),
                        TimeUnit.NANOSECONDS);
                if (finished || remaining <= 0) break;
            } while (true);
            stop(process, children);
            drain.join(1000);
            synchronized (captured) {
                return new Result(finished ? process.exitValue() : -1, !finished,
                        captured.toString(StandardCharsets.UTF_8));
            }
        } finally {
            stop(process, children);
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

    static Map<String, String> inheritedEnvironment(Map<String, String> source, boolean windows) {
        Map<String, String> inherited = new LinkedHashMap<>();
        for (String key : List.of("PATH", "HOME", "TMPDIR", "TMP", "TEMP", "SystemRoot", "WINDIR",
                "SSL_CERT_FILE", "SSL_CERT_DIR")) {
            source.forEach((name, value) -> {
                if (windows ? key.equalsIgnoreCase(name) : key.equals(name)) inherited.put(name, value);
            });
        }
        return inherited;
    }

    private static void rememberChildren(Process process, Map<Long, ProcessHandle> children) {
        process.descendants().forEach(child -> children.putIfAbsent(child.pid(), child));
    }

    private static void stop(Process process, Map<Long, ProcessHandle> children) {
        rememberChildren(process, children);
        children.values().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (process.isAlive()) process.destroyForcibly();
    }
}
