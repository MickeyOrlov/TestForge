package io.testforge.api.fuzz;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for running Schemathesis CLI fuzzing against an OpenAPI document.
 *
 * <p>Specs are not configured here: the fuzz module reads the registry
 * {@code module-api-discovery} already owns
 * ({@code forge.api-discovery.specs.<id>.location}), the same way
 * {@code module-api-explorer} and {@code module-api-codegen} do. One document,
 * one place to point at it.
 *
 * <pre>
 * forge:
 *   api-discovery:
 *     specs:
 *       demo:
 *         location: classpath:/openapi/demo-api.yaml
 *   http:
 *     base-url: https://api.staging.example.test
 *   api-fuzz:
 *     enabled: true
 *     methods: [GET, HEAD, OPTIONS]   # default
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.api-fuzz")
public record ApiFuzzProperties(
        Boolean enabled,
        String outputDir,
        List<String> specs,
        String baseUrl,
        Set<String> methods,
        Boolean allowUnsafeMethods,
        List<String> phases,
        Long seed,
        Integer maxExamples,
        String generationMode,
        Integer maxFailures,
        Integer timeoutSeconds,
        String command,
        String configFile,
        Boolean failOnFindings) {

    /** Methods that may be sent without anyone opting in to anything. */
    public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private static final Set<String> ALLOWED_PHASES = Set.of("examples", "coverage", "fuzzing", "stateful");
    private static final Set<String> ALLOWED_GENERATION_MODES = Set.of("positive", "negative", "all");

    public ApiFuzzProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/api-fuzz";
        }
        specs = List.copyOf(specs == null ? List.of() : specs);
        methods = methods == null || methods.isEmpty()
                ? SAFE_METHODS
                : methods.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (allowUnsafeMethods == null) {
            allowUnsafeMethods = false;
        }
        if (failOnFindings == null) {
            // Findings are a result to read, not a build break, until a team opts in.
            failOnFindings = false;
        }
        if (phases == null || phases.isEmpty()) {
            phases = List.of("coverage", "fuzzing");
        } else {
            List<String> normalizedPhases = phases.stream()
                    .map(p -> p == null ? "" : p.trim().toLowerCase(Locale.ROOT))
                    .toList();
            for (String phase : normalizedPhases) {
                if (!ALLOWED_PHASES.contains(phase)) {
                    throw new IllegalArgumentException("Unknown fuzzing phase: '" + phase + "'. Allowed values are: " + ALLOWED_PHASES);
                }
            }
            phases = List.copyOf(normalizedPhases);
        }
        if (maxExamples == null) {
            maxExamples = 50;
        }
        if (generationMode == null || generationMode.isBlank()) {
            generationMode = "all";
        } else {
            String normalizedMode = generationMode.trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_GENERATION_MODES.contains(normalizedMode)) {
                throw new IllegalArgumentException("Unknown generationMode: '" + generationMode + "'. Allowed values are: " + ALLOWED_GENERATION_MODES);
            }
            generationMode = normalizedMode;
        }
        if (timeoutSeconds == null) {
            timeoutSeconds = 900;
        }
        if (command == null || command.isBlank()) {
            command = "st";
        }
    }

    /**
     * A method is only sent when it is listed <em>and</em>, if it is not one of
     * {@link #SAFE_METHODS}, {@code allow-unsafe-methods} is on. Two keys
     * rather than one because listing {@code POST} is easy to copy from another
     * project's configuration by accident; turning the second key on is not.
     */
    public boolean permits(String method) {
        if (method == null) {
            return false;
        }
        String normalized = method.toUpperCase(Locale.ROOT);
        if (!methods.contains(normalized)) {
            return false;
        }
        return SAFE_METHODS.contains(normalized) || allowUnsafeMethods;
    }

    /**
     * Copies with a different seed. Lives here rather than being hand-written at
     * each call site: this record has fifteen positional components, so every
     * duplicated copy block is a place where a future component silently keeps
     * its old value instead of the intended one.
     */
    public ApiFuzzProperties withSeed(long newSeed) {
        return new ApiFuzzProperties(enabled, outputDir, specs, baseUrl, methods, allowUnsafeMethods,
                phases, newSeed, maxExamples, generationMode, maxFailures, timeoutSeconds,
                command, configFile, failOnFindings);
    }

    /** Copies with a resolved base URL, keeping an explicit one untouched. */
    public ApiFuzzProperties withBaseUrl(String resolved) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return this;
        }
        return new ApiFuzzProperties(enabled, outputDir, specs, resolved, methods, allowUnsafeMethods,
                phases, seed, maxExamples, generationMode, maxFailures, timeoutSeconds,
                command, configFile, failOnFindings);
    }
}
