package io.testforge.api.discovery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for reading a service's OpenAPI document and probing what it
 * describes.
 *
 * <p>Two defaults carry most of the safety of this module and should be
 * understood before changing them: {@code enabled} is false, so the module does
 * nothing at all until an environment profile asks for it; and
 * {@code probe.enabled} is false, so the first run reads the document and
 * writes a catalog <em>without calling a single endpoint</em>. A human reads
 * that catalog, then turns probing on.
 *
 * <pre>
 * forge:
 *   api-discovery:
 *     enabled: true
 *     spec:
 *       source: "path:/v3/api-docs"
 *     probe:
 *       enabled: false        # run 1: catalog only — review it, then flip
 *       methods: [GET]
 *     artifacts:
 *       output-dir: build/api-discovery/current
 *       baseline-dir: build/api-discovery/baseline
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.api-discovery")
public record ApiDiscoveryProperties(
        Boolean enabled,
        String service,
        SpecProperties spec,
        ProbeProperties probe,
        ParameterProperties parameters,
        ArtifactProperties artifacts,
        FailureProperties failOn) {

    public ApiDiscoveryProperties {
        if (enabled == null) {
            enabled = false;
        }
        if (spec == null) {
            spec = new SpecProperties(null, null);
        }
        if (probe == null) {
            probe = new ProbeProperties(null, null, null, null, null, null, null, null, null, null, null);
        }
        if (parameters == null) {
            parameters = new ParameterProperties(null, null, null);
        }
        if (artifacts == null) {
            artifacts = new ArtifactProperties(null, null);
        }
        if (failOn == null) {
            failOn = new FailureProperties(null, null, null, null);
        }
    }

    /**
     * Where the OpenAPI document comes from.
     *
     * <p>{@code source} accepts {@code classpath:openapi/orders.yaml},
     * {@code file:/path/to/openapi.json}, {@code path:/v3/api-docs} (fetched
     * through {@code ApiClient}, so a document behind authentication works with
     * no extra configuration) or an absolute {@code http(s)://} URL.
     *
     * <p>{@code useServerPath} stays false by design. An OpenAPI document is
     * untrusted input, and its {@code servers[].url} must never redirect test
     * traffic to a host the project did not configure. The host always comes
     * from {@code forge.http}.
     */
    public record SpecProperties(String source, Boolean useServerPath) {

        public SpecProperties {
            if (source == null || source.isBlank()) {
                source = "path:/v3/api-docs";
            }
            if (useServerPath == null) {
                useServerPath = false;
            }
        }
    }

    /**
     * What may be called, and what may never be.
     *
     * <p>{@code denyPaths} is a net, not a guarantee: no keyword list catches
     * {@code GET /orders/{id}/cancel}. The guarantees are {@code enabled=false}
     * by default and the catalog review it forces.
     */
    public record ProbeProperties(
            Boolean enabled,
            Set<String> methods,
            List<String> includePaths,
            List<String> denyPaths,
            Boolean skipDeprecated,
            String optOutExtension,
            Integer maxEndpoints,
            Long maxResponseBytes,
            Integer maxShapePaths,
            Set<Integer> snapshotStatuses,
            UnsafeProperties unsafe) {

        public ProbeProperties {
            if (enabled == null) {
                enabled = false;
            }
            methods = upperCase(methods == null ? Set.of("GET") : methods);
            includePaths = List.copyOf(includePaths == null ? List.of("/**") : includePaths);
            denyPaths = List.copyOf(denyPaths == null
                    ? List.of("/**/logout",
                            "/**/actuator/shutdown",
                            "/**/actuator/env",
                            "/**/actuator/heapdump",
                            "/**/actuator/threaddump")
                    : denyPaths);
            if (skipDeprecated == null) {
                skipDeprecated = true;
            }
            if (optOutExtension == null || optOutExtension.isBlank()) {
                optOutExtension = "x-testforge-probe";
            }
            if (maxEndpoints == null || maxEndpoints <= 0) {
                maxEndpoints = 200;
            }
            if (maxResponseBytes == null || maxResponseBytes <= 0) {
                maxResponseBytes = 5L * 1024 * 1024;
            }
            if (maxShapePaths == null || maxShapePaths <= 0) {
                maxShapePaths = 5000;
            }
            snapshotStatuses = Set.copyOf(snapshotStatuses == null ? Set.of(200) : snapshotStatuses);
            if (unsafe == null) {
                unsafe = new UnsafeProperties(null, null, null, null);
            }
        }
    }

    /**
     * Everything outside {@link ProbeProperties#methods()} needs four
     * independently turned keys before a request can leave the JVM. That is
     * deliberate: a discovery run that deletes a real order is the failure mode
     * this module has to make impossible, not unlikely.
     *
     * <p>A catch-all glob is rejected at binding time — {@code includePaths}
     * must name the operations a project consciously accepted.
     */
    public record UnsafeProperties(
            Boolean enabled,
            Set<String> methods,
            List<String> includePaths,
            Boolean allowDelete) {

        private static final Set<String> CATCH_ALL = Set.of("/**", "**", "*", "/*");

        public UnsafeProperties {
            if (enabled == null) {
                enabled = false;
            }
            methods = upperCase(methods == null ? Set.of() : methods);
            includePaths = List.copyOf(includePaths == null ? List.of() : includePaths);
            if (allowDelete == null) {
                allowDelete = false;
            }

            List<String> catchAll = includePaths.stream().filter(CATCH_ALL::contains).toList();
            if (!catchAll.isEmpty()) {
                throw new IllegalArgumentException(
                        "forge.api-discovery.probe.unsafe.include-paths must name explicit paths; %s would allow "
                                .formatted(catchAll)
                                + "unsafe methods across the whole API");
            }
        }
    }

    /**
     * Values for path and query parameters. Nothing is ever guessed: an
     * endpoint whose parameters cannot be resolved is reported as skipped, with
     * a ready-to-paste YAML block in the report.
     *
     * <p>{@code useSpecExamples} is off because examples in real documents are
     * usually placeholders that produce 404 noise — and occasionally a
     * production identifier somebody pasted in.
     */
    public record ParameterProperties(
            Boolean useSpecExamples,
            Map<String, String> defaults,
            Map<String, Map<String, String>> operations) {

        public ParameterProperties {
            if (useSpecExamples == null) {
                useSpecExamples = false;
            }
            defaults = Map.copyOf(defaults == null ? Map.of() : defaults);

            Map<String, Map<String, String>> copied = new LinkedHashMap<>();
            if (operations != null) {
                operations.forEach((operationId, values) ->
                        copied.put(operationId, Map.copyOf(values == null ? Map.of() : values)));
            }
            operations = Map.copyOf(copied);
        }

        /**
         * Operation-specific value first, then the shared default. An
         * operation without an {@code operationId} is common in real
         * documents, so a null id must resolve to the defaults rather than
         * blow up.
         */
        public String find(String operationId, String parameterName) {
            Map<String, String> forOperation = operationId == null
                    ? Map.of()
                    : operations.getOrDefault(operationId, Map.of());

            String specific = forOperation.get(parameterName);
            return specific != null ? specific : defaults.get(parameterName);
        }
    }

    /**
     * Artifact directories. Both live under {@code build/} because that is the
     * only git-ignored place; the baseline directory is read-only input a human
     * puts there, and nothing in this module ever writes to it.
     */
    public record ArtifactProperties(String outputDir, String baselineDir) {

        public ArtifactProperties {
            if (outputDir == null || outputDir.isBlank()) {
                outputDir = "build/api-discovery/current";
            }
            if (baselineDir == null || baselineDir.isBlank()) {
                baselineDir = "build/api-discovery/baseline";
            }
        }
    }

    /**
     * {@code undeclaredFields} is the one flag that defaults to false: the
     * first run against an unfamiliar backend lights up everywhere, and a hard
     * failure on day one teaches a team to switch the module off. Turn it on
     * after the first baseline review.
     */
    public record FailureProperties(
            Boolean shapeDiff,
            Boolean specDrift,
            Boolean undeclaredFields,
            Boolean probeError) {

        public FailureProperties {
            if (shapeDiff == null) {
                shapeDiff = true;
            }
            if (specDrift == null) {
                specDrift = true;
            }
            if (undeclaredFields == null) {
                undeclaredFields = false;
            }
            if (probeError == null) {
                probeError = true;
            }
        }
    }

    private static Set<String> upperCase(Set<String> values) {
        return values.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
