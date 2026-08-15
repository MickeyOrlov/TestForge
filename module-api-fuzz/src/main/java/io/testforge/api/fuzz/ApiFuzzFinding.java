package io.testforge.api.fuzz;

/**
 * A record summarising one failing check.
 */
public record ApiFuzzFinding(
    String operationLabel,
    String phase,
    String method,
    String path,
    String checkName,
    String message
) {
}
