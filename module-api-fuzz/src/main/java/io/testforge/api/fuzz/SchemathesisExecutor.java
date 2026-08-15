package io.testforge.api.fuzz;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class SchemathesisExecutor {
    private final ProcessRunner runner;
    private final String executable;

    public SchemathesisExecutor(ProcessRunner runner, String executable) {
        this.runner = runner;
        this.executable = executable;
    }

    public SchemathesisExecutor(ProcessRunner runner) {
        this(runner, "st");
    }

    public SchemathesisVersion probeVersion() {
        ProcessResult result;
        try {
            result = runner.run(List.of(executable, "--version"), null, Map.of(), Duration.ofSeconds(10));
        } catch (ApiFuzzException e) {
            if (e.getCause() instanceof java.io.IOException) {
                throw new ApiFuzzException(
                        "Schemathesis executable '" + executable + "' not found. Install it with: uv tool install schemathesis (tested with 4.24.3).",
                        e
                );
            }
            throw e;
        }

        if (result.timedOut()) {
            throw new ApiFuzzException("Timeout probing Schemathesis version");
        }
        if (result.exitCode() != 0) {
            throw new ApiFuzzException(
                    "Schemathesis executable '" + executable + "' not found or failed. Install it with: uv tool install schemathesis (tested with 4.24.3). Exit code: " + result.exitCode() + ". Stderr: " + result.stderr()
            );
        }

        SchemathesisVersion version = SchemathesisVersion.parse(result.stdout());
        if (!version.isSupported()) {
            throw new ApiFuzzException("Unsupported Schemathesis version: " + version.raw() + ". Major version 4 is required.");
        }

        return version;
    }

    public ProcessResult run(List<String> args, Path workingDir, Duration timeout) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.addAll(args);
        return runner.run(command, workingDir, Map.of(), timeout);
    }
}
