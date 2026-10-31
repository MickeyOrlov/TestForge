package io.testforge.api.discovery;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "forge.api-discovery")
public record ApiDiscoveryProperties(
        Boolean enabled,
        String outputDir,
        String baselineDir,
        Boolean failOnCatalogDiff,
        Boolean failOnShapeDiff,
        Map<String, Spec> specs,
        Boolean failOnBreakingOnly) {

    /** Without {@code fail-on-breaking-only}: every drift counts, as before. */
    public ApiDiscoveryProperties(Boolean enabled, String outputDir, String baselineDir, Boolean failOnCatalogDiff,
                                  Boolean failOnShapeDiff, Map<String, Spec> specs) {
        this(enabled, outputDir, baselineDir, failOnCatalogDiff, failOnShapeDiff, specs, null);
    }

    @ConstructorBinding
    public ApiDiscoveryProperties {
        if (failOnBreakingOnly == null) {
            failOnBreakingOnly = false;
        }
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/api-discovery/current";
        }
        if (baselineDir == null || baselineDir.isBlank()) {
            baselineDir = "build/api-discovery/baseline";
        }
        if (failOnCatalogDiff == null) {
            failOnCatalogDiff = true;
        }
        if (failOnShapeDiff == null) {
            failOnShapeDiff = true;
        }
        specs = Map.copyOf(specs == null ? Map.of() : specs);
    }

    public record Spec(String location) {
    }
}
