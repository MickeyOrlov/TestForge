package io.testforge.api.fuzz;

/**
 * Outcome of an API fuzzing run.
 */
public enum ApiFuzzOutcome {
    /**
     * The run completed successfully with no findings.
     */
    PASSED,
    
    /**
     * The run completed but the API misbehaved (a real result).
     */
    FINDINGS,
    
    /**
     * Schemathesis could not do its job (target unreachable, crash). This is NOT an API finding.
     */
    EXECUTION_ERROR,
    
    /**
     * There was a configuration error (e.g., usage error).
     */
    CONFIGURATION_ERROR
}
