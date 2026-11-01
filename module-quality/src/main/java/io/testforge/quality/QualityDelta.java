package io.testforge.quality;

import java.util.List;

/**
 * A dimension against the accepted baseline.
 *
 * @param baselineScore the score in the baseline run, {@code null} when it did not measure this dimension
 * @param newIssues     issues the baseline did not have and nobody accepted — what the gate fails on
 * @param acceptedIssues issues let through by {@code forge.quality.accepted}
 * @param knownIssues   issues the baseline already had
 * @param resolvedIssues issues the baseline had and this run does not
 */
public record QualityDelta(Integer baselineScore, List<String> newIssues, List<String> acceptedIssues,
                           List<String> knownIssues, List<String> resolvedIssues) {

    public QualityDelta {
        newIssues = List.copyOf(newIssues);
        acceptedIssues = List.copyOf(acceptedIssues);
        knownIssues = List.copyOf(knownIssues);
        resolvedIssues = List.copyOf(resolvedIssues);
    }
}
