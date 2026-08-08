package io.testforge.api.fuzz;

import io.testforge.api.explorer.ApiExplorerProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for fuzzing an API against its own OpenAPI document.
 *
 * <p>Specs come from {@code forge.api-discovery.specs}, and baseline parameter
 * values reuse the explorer's own {@code ParameterProperties} shape — a project
 * that already configured an id for exploration does not configure it twice.
 *
 * <pre>
 * forge:
 *   api-fuzz:
 *     enabled: true
 *     seed: 20260101
 *     max-cases-per-operation: 20
 *     fail-on:
 *       server-error: true
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.api-fuzz")
public record ApiFuzzProperties(
        Boolean enabled,
        String outputDir,
        String service,
        List<String> specs,
        Long seed,
        Set<String> methods,
        Boolean allowUnsafeMethods,
        List<String> includePaths,
        List<String> excludePaths,
        Integer maxOperations,
        Integer maxCasesPerOperation,
        Integer maxBodyChars,
        List<String> onlyCases,
        ApiExplorerProperties.ParameterProperties parameters,
        FailureProperties failOn) {

    public ApiFuzzProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/api-fuzz";
        }
        specs = List.copyOf(specs == null ? List.of() : specs);
        if (seed == null) {
            seed = 0L;
        }
        methods = methods == null || methods.isEmpty()
                ? ApiExplorerProperties.SAFE_METHODS
                : methods.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (allowUnsafeMethods == null) {
            allowUnsafeMethods = false;
        }
        includePaths = List.copyOf(includePaths == null ? List.of("/**") : includePaths);
        excludePaths = List.copyOf(excludePaths == null ? List.of() : excludePaths);
        if (maxOperations == null || maxOperations <= 0) {
            maxOperations = 50;
        }
        if (maxCasesPerOperation == null || maxCasesPerOperation <= 0) {
            maxCasesPerOperation = 20;
        }
        if (maxBodyChars == null || maxBodyChars <= 0) {
            maxBodyChars = 4000;
        }
        onlyCases = List.copyOf(onlyCases == null ? List.of() : onlyCases);
        if (parameters == null) {
            parameters = new ApiExplorerProperties.ParameterProperties(null, null);
        }
        if (failOn == null) {
            failOn = new FailureProperties(null, null, null, null, null, null);
        }
    }

    /** True when this run is reproducing specific cases rather than sweeping. */
    public boolean replaying() {
        return !onlyCases.isEmpty();
    }

    /**
     * Only a crash fails the build out of the box. The rest are conversations
     * to have with the service team first: an over-permissive field is usually
     * a real defect, but the first sweep of an unfamiliar API finds enough of
     * them that failing immediately teaches people to turn the module off.
     *
     * <p>Keyed by verdict for validation conclusions and by evidence for facts
     * about the response, because those are different things — a 500 is not a
     * verdict about validation, it is a crash that happened to occur during one.
     */
    public record FailureProperties(
            Boolean serverError,
            Boolean transportFailure,
            Boolean overPermissive,
            Boolean overStrict,
            Boolean undocumentedResponse,
            Boolean inputReflected) {

        public FailureProperties {
            if (serverError == null) {
                serverError = true;
            }
            if (transportFailure == null) {
                transportFailure = true;
            }
            if (overPermissive == null) {
                overPermissive = false;
            }
            if (overStrict == null) {
                overStrict = false;
            }
            if (undocumentedResponse == null) {
                undocumentedResponse = false;
            }
            if (inputReflected == null) {
                inputReflected = false;
            }
        }

        /** An inconclusive case never fails a build: it is an admission, not a defect. */
        public boolean fails(FuzzObservation observation) {
            if (observation.verdict() == FuzzVerdict.OVER_PERMISSIVE && overPermissive) {
                return true;
            }
            if (observation.verdict() == FuzzVerdict.OVER_STRICT && overStrict) {
                return true;
            }
            return (serverError && observation.has(FuzzEvidenceKind.SERVER_ERROR))
                    || (transportFailure && observation.has(FuzzEvidenceKind.TRANSPORT_FAILURE))
                    || (undocumentedResponse && observation.has(FuzzEvidenceKind.UNDOCUMENTED_RESPONSE))
                    || (inputReflected && observation.has(FuzzEvidenceKind.INPUT_REFLECTED));
        }
    }
}
