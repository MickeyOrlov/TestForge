package io.testforge.api.explorer;

/**
 * The line one operation gets in {@code report.json}.
 *
 * <p>Summaries carry no headers and no bodies. The full observation lives in
 * its own file next to the report, so the report stays readable at a glance and
 * the thing a reviewer opens first is not a wall of payloads.
 */
public record ObservationSummary(
        String operationId,
        String key,
        ExplorerOutcome outcome,
        Integer status,
        Long durationMillis,
        SkipReason skipReason,
        String reason,
        int mismatches,
        String artifact) {

    public static ObservationSummary of(ApiObservation observation, String artifact) {
        return new ObservationSummary(
                observation.operationId(),
                observation.key(),
                observation.outcome(),
                observation.status(),
                observation.durationMillis(),
                observation.skipReason(),
                observation.reason(),
                observation.mismatches().size(),
                artifact);
    }
}
