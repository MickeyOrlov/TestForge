package io.testforge.reporting;

import java.time.Instant;

/**
 * Diagnostic problem recorded when an artifact operation fails.
 *
 * @param operation the sink operation that failed (e.g. "write", "register", "directoryFor")
 * @param message failure message description
 * @param cause the underlying exception or error, if any
 * @param timestamp timestamp when the problem occurred
 */
public record ReportingProblem(
        String operation,
        String message,
        Throwable cause,
        Instant timestamp
) {
    public ReportingProblem {
        operation = (operation != null && !operation.isBlank()) ? operation : "unknown";
        if (message == null && cause != null) {
            message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getName();
        }
        if (message == null) {
            message = "Unknown reporting problem";
        }
        // Problems are copied into manifest.json. Filesystem exception messages carry
        // ABSOLUTE paths, which would leak the local username into CI output.
        message = DiagnosticText.sanitise(message);
        if (timestamp == null) {
            timestamp = Instant.now();
        }
    }

    public ReportingProblem(String operation, Throwable cause) {
        this(
                operation,
                cause != null && cause.getMessage() != null ? cause.getMessage() : (cause != null ? cause.getClass().getName() : "Unknown reporting problem"),
                cause,
                Instant.now()
        );
    }
}
