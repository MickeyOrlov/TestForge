package io.testforge.api.fuzz;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);

            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "", "", true);
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            return new ProcessResult(process.exitValue(), stdout, stderr, false);
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to start process: " + command.get(0), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiFuzzException("Process interrupted: " + command.get(0), e);
        }
    }
}
