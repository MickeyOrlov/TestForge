package io.testforge.api.fuzz;

import java.util.List;

/**
 * The result of one fuzz run.
 *
 * <p>{@code seed} is recorded at the top because it is half of what a
 * reproduction needs — the other half is the case id, and both are printed in
 * the markdown next to every finding.
 */
public record ApiFuzzReport(
        boolean enabled,
        String generatedAt,
        boolean healthy,
        long seed,
        List<SpecFuzzReport> specs,
        String outputDir,
        String reportJson,
        String reportMarkdown) {

    public ApiFuzzReport {
        specs = List.copyOf(specs == null ? List.of() : specs);
    }

    public List<FuzzObservation> findings() {
        return specs.stream()
                .flatMap(spec -> spec.operations().stream())
                .flatMap(operation -> operation.observations().stream())
                .filter(FuzzObservation::finding)
                .toList();
    }

    /** Cases the run could not draw a conclusion from, and why. */
    public List<FuzzObservation> inconclusive() {
        return specs.stream()
                .flatMap(spec -> spec.operations().stream())
                .flatMap(operation -> operation.observations().stream())
                .filter(observation -> observation.verdict() == FuzzVerdict.INCONCLUSIVE)
                .toList();
    }

    /** One spec's worth of fuzzing. */
    public record SpecFuzzReport(
            String specId,
            String location,
            String baseUrl,
            String fingerprint,
            boolean failed,
            int operationCount,
            int cases,
            int findings,
            List<OperationFuzzReport> operationReports,
            List<ReproductionManifest> reproduction,
            String error) {

        public SpecFuzzReport {
            operationReports = List.copyOf(operationReports == null ? List.of() : operationReports);
            reproduction = List.copyOf(reproduction == null ? List.of() : reproduction);
        }

        public List<OperationFuzzReport> operations() {
            return operationReports;
        }

        public static SpecFuzzReport broken(String specId, String location, String baseUrl, String error) {
            return new SpecFuzzReport(specId, location, baseUrl, null, true, 0, 0, 0,
                    List.of(), List.of(), error);
        }
    }
}
