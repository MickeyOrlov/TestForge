package io.testforge.api.fuzz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class DefaultProcessRunner implements ProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(DefaultProcessRunner.class);

    @Override
    public ProcessResult run(List<String> command, Path workingDir, Map<String, String> extraEnv, Duration timeout) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("Command cannot be empty");
        }

        // We only log the executable and the argument count.
        // We MUST NEVER log the full command at INFO to prevent logging credentials.
        log.info("Executing {} with {} arguments", command.get(0), command.size() - 1);

        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        if (extraEnv != null) {
            pb.environment().putAll(extraEnv);
        }

        try {
            Process process = pb.start();

            // Both pipes must be drained WHILE the process runs. A child that
            // fills the OS pipe buffer blocks on write and never exits, so
            // reading only after waitFor() turns a merely verbose run into a
            // reported timeout. Schemathesis is routinely verbose, so this is
            // the normal path, not an edge case.
            StreamPump out = StreamPump.start(process.getInputStream(), "stdout");
            StreamPump err = StreamPump.start(process.getErrorStream(), "stderr");

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                // Keep whatever was captured: the tail of a timed-out run is
                // usually the only clue about where it got stuck.
                return new ProcessResult(-1, out.join(GRACE), err.join(GRACE), true);
            }

            return new ProcessResult(process.exitValue(), out.join(GRACE), err.join(GRACE), false);
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to start process: " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiFuzzException("Process interrupted: " + command.get(0), e);
        }
    }

    /** How long to wait for a drained pipe to close after the process ends. */
    private static final Duration GRACE = Duration.ofSeconds(5);

    /**
     * Reads one stream to completion on its own thread so neither pipe can fill
     * while the other is being read.
     */
    private static final class StreamPump {

        private final Thread thread;
        private final StringBuilder sink = new StringBuilder();

        private StreamPump(InputStream stream, String name) {
            this.thread = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        synchronized (sink) {
                            sink.append(buffer, 0, read);
                        }
                    }
                } catch (IOException e) {
                    // The process was destroyed mid-read; whatever we captured
                    // before that is still worth returning.
                    log.debug("stopped reading {}: {}", name, e.getMessage());
                }
            }, "schemathesis-" + name);
            this.thread.setDaemon(true);
        }

        static StreamPump start(InputStream stream, String name) {
            StreamPump pump = new StreamPump(stream, name);
            pump.thread.start();
            return pump;
        }

        String join(Duration grace) throws InterruptedException {
            thread.join(grace.toMillis());
            synchronized (sink) {
                return sink.toString();
            }
        }
    }
}
