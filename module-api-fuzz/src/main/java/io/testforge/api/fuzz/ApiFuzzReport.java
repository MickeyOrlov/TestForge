package io.testforge.api.fuzz;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A record summarising one API fuzzing run.
 */
public record ApiFuzzReport(
    String runId,
    String specId,
    ApiFuzzOutcome outcome,
    String schemathesisVersion,
    Long seed,
    List<String> phases,
    int totalScenarios,
    int failedScenarios,
    List<ApiFuzzFinding> findings,
    List<String> errors,
    Map<String, Path> artifacts,
    Duration duration
) {
    /**
     * @return true if the outcome indicates that findings were discovered.
     */
    public boolean hasFindings() {
        return outcome == ApiFuzzOutcome.FINDINGS || (findings != null && !findings.isEmpty());
    }

    /** The findings grouped by spec, check and operation, most frequent first. */
    public List<ApiFuzzFindingGroup> findingGroups() {
        return ApiFuzzFindingGroup.of(findings);
    }
}
