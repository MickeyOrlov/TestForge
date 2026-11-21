package io.testforge.load;

import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Project-selectable validity rules; k6 still owns every native threshold and its verdict.
 *
 * @param failOnEmptyThresholds a threshold on a sub-metric no sample reached — a tag filter that matches
 *                              nothing, by a typo or a system tag the run left out — is not a passed SLO:
 *                              k6 itself reports it as passed
 */
public record LoadPolicyProperties(Boolean requireHttp, Boolean requireThresholds, Boolean failOnDroppedIterations,
                                   Boolean failOnEmptyThresholds) {
    @ConstructorBinding
    public LoadPolicyProperties {
        requireHttp = requireHttp == null ? true : requireHttp;
        requireThresholds = requireThresholds == null ? true : requireThresholds;
        failOnDroppedIterations = failOnDroppedIterations == null ? true : failOnDroppedIterations;
        failOnEmptyThresholds = failOnEmptyThresholds == null ? true : failOnEmptyThresholds;
    }

    public LoadPolicyProperties(Boolean requireHttp, Boolean requireThresholds, Boolean failOnDroppedIterations) {
        this(requireHttp, requireThresholds, failOnDroppedIterations, null);
    }
}
