package io.testforge.api.explorer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for exploring a live API against its OpenAPI document.
 *
 * <p>Specs are not configured here: the explorer reads the registry
 * {@code module-api-discovery} already owns
 * ({@code forge.api-discovery.specs.<id>.location}), the same way
 * {@code module-api-codegen} does. One document, one place to point at it.
 *
 * <pre>
 * forge:
 *   api-discovery:
 *     specs:
 *       demo:
 *         location: classpath:/openapi/demo-api.yaml
 *   http:
 *     base-url: https://api.staging.example.test
 *   api-explorer:
 *     enabled: true
 *     methods: [GET, HEAD, OPTIONS]   # default
 *     parameters:
 *       defaults:
 *         taskId: "task-1"
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.api-explorer")
public record ApiExplorerProperties(
        Boolean enabled,
        String outputDir,
        String service,
        List<String> specs,
        Set<String> methods,
        Boolean allowUnsafeMethods,
        List<String> includePaths,
        List<String> excludePaths,
        Integer maxOperations,
        Integer maxBodyChars,
        ParameterProperties parameters,
        FailureProperties failOn) {

    /** Methods that may be sent without anyone opting in to anything. */
    public static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    public ApiExplorerProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (outputDir == null || outputDir.isBlank()) {
            outputDir = "build/api-explorer";
        }
        specs = List.copyOf(specs == null ? List.of() : specs);
        methods = methods == null || methods.isEmpty()
                ? SAFE_METHODS
                : methods.stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (allowUnsafeMethods == null) {
            allowUnsafeMethods = false;
        }
        includePaths = List.copyOf(includePaths == null ? List.of("/**") : includePaths);
        excludePaths = List.copyOf(excludePaths == null ? List.of() : excludePaths);
        if (maxOperations == null || maxOperations <= 0) {
            maxOperations = 200;
        }
        if (maxBodyChars == null || maxBodyChars <= 0) {
            maxBodyChars = 4000;
        }
        if (parameters == null) {
            parameters = new ParameterProperties(null, null);
        }
        if (failOn == null) {
            failOn = new FailureProperties(null, null);
        }
    }

    /**
     * A method is only sent when it is listed <em>and</em>, if it is not one of
     * {@link #SAFE_METHODS}, {@code allow-unsafe-methods} is on. Two keys
     * rather than one because listing {@code POST} is easy to copy from another
     * project's configuration by accident; turning the second key on is not.
     */
    public boolean permits(String method) {
        String normalized = method.toUpperCase(Locale.ROOT);
        if (!methods.contains(normalized)) {
            return false;
        }
        return SAFE_METHODS.contains(normalized) || allowUnsafeMethods;
    }

    /** Values a human supplied for path and query parameters. */
    public record ParameterProperties(
            Map<String, String> defaults,
            Map<String, Map<String, String>> operations) {

        public ParameterProperties {
            defaults = Map.copyOf(defaults == null ? Map.of() : defaults);

            Map<String, Map<String, String>> copied = new LinkedHashMap<>();
            if (operations != null) {
                operations.forEach((operationId, values) ->
                        copied.put(operationId, Map.copyOf(values == null ? Map.of() : values)));
            }
            operations = Map.copyOf(copied);
        }

        /** Operation-specific value first, then the shared default. */
        public String find(String operationId, String parameterName) {
            Map<String, String> forOperation = operationId == null
                    ? Map.of()
                    : operations.getOrDefault(operationId, Map.of());

            String specific = forOperation.get(parameterName);
            return specific != null ? specific : defaults.get(parameterName);
        }
    }

    /**
     * A contract mismatch does not fail the run by default: the first
     * exploration of an unfamiliar API finds plenty, and a red build on day one
     * teaches a team to switch the module off rather than to fix the document.
     * A request that could not be executed at all is a different matter.
     */
    public record FailureProperties(Boolean contractMismatch, Boolean failure) {

        public FailureProperties {
            if (contractMismatch == null) {
                contractMismatch = false;
            }
            if (failure == null) {
                failure = true;
            }
        }
    }
}
