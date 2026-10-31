package io.testforge.api.fuzz;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A record summarising one API fuzzing run.
 *
 * @param acceptedFindings the run's {@code forge.api-fuzz.accepted-findings},
 *        normalized; see {@link #newFindingGroups()}
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
    Duration duration,
    List<String> acceptedFindings
) {

    public ApiFuzzReport {
        acceptedFindings = AcceptedFindings.normalize(acceptedFindings);
    }

    public ApiFuzzReport(
            String runId, String specId, ApiFuzzOutcome outcome, String schemathesisVersion, Long seed,
            List<String> phases, int totalScenarios, int failedScenarios, List<ApiFuzzFinding> findings,
            List<String> errors, Map<String, Path> artifacts, Duration duration) {
        this(runId, specId, outcome, schemathesisVersion, seed, phases, totalScenarios, failedScenarios,
                findings, errors, artifacts, duration, List.of());
    }

    /**
     * @return true if the outcome indicates that findings were discovered.
     */
    public boolean hasFindings() {
        return outcome == ApiFuzzOutcome.FINDINGS || (findings != null && !findings.isEmpty());
    }

    /** The findings grouped by spec, check and operation, most frequent first; accepted ones marked. */
    public List<ApiFuzzFindingGroup> findingGroups() {
        return ApiFuzzFindingGroup.of(findings, acceptedFindings);
    }

    /** The groups no accepted entry covers: what {@code fail-on-findings} fails on. */
    public List<ApiFuzzFindingGroup> newFindingGroups() {
        return findingGroups().stream().filter(group -> !group.accepted()).toList();
    }

    /**
     * Accepted entries that matched nothing in this run: fixed, or renamed —
     * either way the entry is due for removal. Empty when the run did not
     * complete, since a spec that never ran proves nothing about its findings.
     */
    public List<String> staleAcceptedFindings() {
        if (outcome == ApiFuzzOutcome.EXECUTION_ERROR || outcome == ApiFuzzOutcome.CONFIGURATION_ERROR) {
            return List.of();
        }
        List<ApiFuzzFindingGroup> groups = findingGroups();
        return acceptedFindings.stream()
                .filter(entry -> groups.stream()
                        .noneMatch(group -> AcceptedFindings.matches(entry, group.specId(), group.key())))
                .toList();
    }
}
