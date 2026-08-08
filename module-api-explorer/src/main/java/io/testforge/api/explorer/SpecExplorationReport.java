package io.testforge.api.explorer;

import java.util.List;

/**
 * What exploring one document found.
 *
 * <p>The counts are the answer to the question this module exists for: of
 * everything the document describes, how much is actually reachable, how much
 * answers differently than promised, and how much nobody could even call.
 */
public record SpecExplorationReport(
        String specId,
        String location,
        String baseUrl,
        boolean failed,
        int operations,
        int passed,
        int contractMismatch,
        int failedCalls,
        int skipped,
        List<ObservationSummary> observations,
        String observationsDir,
        String error) {

    public SpecExplorationReport {
        observations = List.copyOf(observations == null ? List.of() : observations);
    }

    /** Spec that could not be parsed or reached at all. */
    public static SpecExplorationReport broken(String specId, String location, String baseUrl, String error) {
        return new SpecExplorationReport(specId, location, baseUrl, true, 0, 0, 0, 0, 0,
                List.of(), null, error);
    }
}
