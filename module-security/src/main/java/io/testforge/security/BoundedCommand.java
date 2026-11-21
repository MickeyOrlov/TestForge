package io.testforge.security;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One external command (the Docker CLI) with a deadline. Its console output is discarded unless
 * redirected to a private file: scanner consoles may echo targets and observed values.
 */
final class BoundedCommand {
    private BoundedCommand() {}

    record Outcome(int exitCode, boolean timedOut) {
        boolean success() {
            return exitCode == 0 && !timedOut;
        }
    }

    static Outcome run(List<String> args, Duration timeout) {
        return run(args, timeout, null, null);
    }

    static Outcome run(List<String> args, Duration timeout, Path input, Path output) {
        Process process = null;
        try {
            ProcessBuilder builder =
                    new ProcessBuilder(args)
                            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                            .redirectError(ProcessBuilder.Redirect.DISCARD);
            if (input != null) builder.redirectInput(input.toFile());
            if (output != null) builder.redirectOutput(output.toFile());
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Outcome(-1, true);
            }
            return new Outcome(process.exitValue(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Outcome(-1, false);
        } catch (Exception e) {
            return new Outcome(-1, false);
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
