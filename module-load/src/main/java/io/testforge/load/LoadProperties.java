package io.testforge.load;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Local k6 execution; scenarios, pacing and thresholds belong to k6, never to TestForge. */
@ConfigurationProperties(prefix = "forge.load")
public record LoadProperties(boolean enabled, String command, String expectedVersion, String script,
                             String baseUrl, String outputDir, Duration timeout,
                             Map<String, String> environment, boolean htmlReport, PrometheusProperties prometheus,
                             LoadPolicyProperties policy) {
    @ConstructorBinding
    public LoadProperties {
        command = command == null || command.isBlank() ? "k6" : command;
        expectedVersion = expectedVersion == null || expectedVersion.isBlank() ? "2.3.0" : expectedVersion;
        outputDir = outputDir == null || outputDir.isBlank() ? "build/load" : outputDir;
        timeout = timeout == null ? Duration.ofMinutes(15) : timeout;
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() == 0) {
            throw new IllegalArgumentException("forge.load.timeout must be at least 1ms");
        }
        environment = Map.copyOf(environment == null ? Map.of() : environment);
        prometheus = prometheus == null ? new PrometheusProperties(false, null, null, null, null, null) : prometheus;
        policy = policy == null ? new LoadPolicyProperties(null, null, null) : policy;
        for (String key : environment.keySet()) {
            if (!key.matches("[A-Za-z_][A-Za-z0-9_]*") || key.startsWith("K6_")
                    || key.startsWith("TESTFORGE_LOAD_") || key.equals("HOME") || key.equals("PATH")
                    || key.equals("LD_PRELOAD") || key.equals("DYLD_INSERT_LIBRARIES")) {
                throw new IllegalArgumentException("forge.load.environment contains a reserved or invalid key");
            }
        }
    }

    public LoadProperties(boolean enabled, String command, String expectedVersion, String script,
                          String baseUrl, String outputDir, Duration timeout,
                          Map<String, String> environment, boolean htmlReport, PrometheusProperties prometheus) {
        this(enabled, command, expectedVersion, script, baseUrl, outputDir, timeout, environment, htmlReport, prometheus, null);
    }

    public LoadProperties(boolean enabled, String command, String expectedVersion, String script,
                          String baseUrl, String outputDir, Duration timeout,
                          Map<String, String> environment, boolean htmlReport) {
        this(enabled, command, expectedVersion, script, baseUrl, outputDir, timeout, environment, htmlReport, null);
    }

    void validateRun() {
        if (!enabled) {
            throw new LoadException("Load execution requires forge.load.enabled=true");
        }
        if (script == null || script.isBlank()) {
            throw new LoadException("Set forge.load.script to a trusted local k6 script");
        }
        if ((baseUrl == null || baseUrl.isBlank()) && !policy.requireHttp()) {
            return;
        }
        try {
            URI target = URI.create(baseUrl == null ? "" : baseUrl);
            if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme()))
                    || target.getHost() == null || target.getUserInfo() != null
                    || target.getQuery() != null || target.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new LoadException("forge.load.base-url must be an HTTP(S) URL without credentials, query or fragment");
        }
    }

    /** Credentials must not escape through a record's generated toString(). */
    @Override
    public String toString() {
        return "LoadProperties[enabled=" + enabled + ", environment=<redacted>]";
    }
}
