package io.testforge.api.fuzz;

public record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {
}
