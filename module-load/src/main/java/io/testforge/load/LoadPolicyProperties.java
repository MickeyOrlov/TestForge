package io.testforge.load;

/** Project-selectable validity rules; k6 still owns every native threshold and its verdict. */
public record LoadPolicyProperties(Boolean requireHttp, Boolean requireThresholds, Boolean failOnDroppedIterations) {
    public LoadPolicyProperties {
        requireHttp = requireHttp == null ? true : requireHttp;
        requireThresholds = requireThresholds == null ? true : requireThresholds;
        failOnDroppedIterations = failOnDroppedIterations == null ? true : failOnDroppedIterations;
    }
}
