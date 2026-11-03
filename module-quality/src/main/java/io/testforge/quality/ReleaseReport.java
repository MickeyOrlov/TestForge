package io.testforge.quality;

import java.util.List;

/**
 * Whether a build can ship, by absolute rules: issues the baseline already knew count, a required
 * dimension nobody measured makes the answer UNKNOWN, accepted issues are risks the release carries —
 * listed with their owner, reason and expiry, never hidden.
 *
 * @param verdict     READY, NOT_READY or UNKNOWN
 * @param blockers    why it is NOT_READY, one line per dimension
 * @param unmeasured  required dimensions without a measurement, with the reason
 * @param dimensions  each dimension's state and its part in the release
 * @param risks       accepted issues the release ships with
 */
@com.fasterxml.jackson.annotation.JsonPropertyOrder({"formatVersion", "runId", "verdict", "blockers", "unmeasured",
        "risks", "dimensions", "reportMarkdown", "reportJson"})
public record ReleaseReport(String runId, Verdict verdict, List<String> blockers, List<String> unmeasured,
                            List<Dimension> dimensions, List<Risk> risks, String reportMarkdown, String reportJson) {

    public static final int FORMAT_VERSION = 1;

    public enum Verdict { READY, NOT_READY, UNKNOWN }

    /**
     * @param role       {@code required}, {@code blocking} ({@code fail-on} or a release limit) or {@code report only}
     * @param openIssues issues not accepted, the baseline's included
     */
    public record Dimension(String name, QualityStatus status, Integer score, String role, int openIssues) {
    }

    /** An accepted issue: what, whose, why and until when; {@code expired} ones block like any open issue. */
    public record Risk(String dimension, String issue, String owner, String reason, String until, boolean expired) {
    }

    public ReleaseReport {
        blockers = List.copyOf(blockers);
        unmeasured = List.copyOf(unmeasured);
        dimensions = List.copyOf(dimensions);
        risks = List.copyOf(risks);
    }

    @com.fasterxml.jackson.annotation.JsonProperty("formatVersion")
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    ReleaseReport withFiles(String markdown, String json) {
        return new ReleaseReport(runId, verdict, blockers, unmeasured, dimensions, risks, markdown, json);
    }
}
