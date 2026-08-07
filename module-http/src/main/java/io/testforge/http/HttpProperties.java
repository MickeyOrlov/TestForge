package io.testforge.http;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API client settings: where requests go, what they carry, how they are logged.
 *
 * <pre>
 * forge:
 *   http:
 *     base-url: https://api.staging.example.test
 *     services:
 *       payments:
 *         base-url: https://payments.staging.example.test
 *     connect-timeout: 5s
 *     read-timeout: 30s
 *     headers:
 *       "[X-Tenant]": demo
 *     scope:
 *       json-path: "$.metadata.test_scope"   # defaults to forge.mock.scope-json-path
 *     correlation:
 *       header: X-Request-Id
 *     logging:
 *       bodies: true
 *     retry:
 *       enabled: false
 * </pre>
 *
 * <p>Header maps need the bracket syntax shown above: Spring's relaxed binding
 * would otherwise rewrite {@code X-Tenant} into a canonical lower-case form.
 */
@ConfigurationProperties(prefix = "forge.http")
public record HttpProperties(
        String baseUrl,
        Map<String, String> headers,
        Map<String, ServiceProperties> services,
        Duration connectTimeout,
        Duration readTimeout,
        ScopeProperties scope,
        CorrelationProperties correlation,
        LoggingProperties logging,
        RetryProperties retry) {

    public HttpProperties {
        headers = Map.copyOf(headers == null ? Map.of() : headers);
        services = Map.copyOf(services == null ? Map.of() : services);
        if (connectTimeout == null) {
            connectTimeout = Duration.ofSeconds(5);
        }
        if (readTimeout == null) {
            readTimeout = Duration.ofSeconds(30);
        }
        if (scope == null) {
            scope = new ScopeProperties(null, null, null);
        }
        if (correlation == null) {
            correlation = new CorrelationProperties(null, null);
        }
        if (logging == null) {
            logging = new LoggingProperties(null, null, null, null, null);
        }
        if (retry == null) {
            retry = new RetryProperties(null, null, null, null, null);
        }
    }

    /**
     * Base URL for the named service, or the default one when {@code service}
     * is {@code null}. Fails loudly rather than silently sending a request to
     * the wrong host.
     */
    public String resolveBaseUrl(String service) {
        String resolved = service == null ? baseUrl : serviceProperties(service).baseUrl();
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalStateException(service == null
                    ? "No forge.http.base-url configured; set it for this environment or call request(\"<service>\")"
                    : "No forge.http.services.%s.base-url configured".formatted(service));
        }
        return resolved;
    }

    /** Default headers merged with the named service's own headers. */
    public Map<String, String> resolveHeaders(String service) {
        Map<String, String> merged = new LinkedHashMap<>(headers);
        if (service != null) {
            merged.putAll(serviceProperties(service).headers());
        }
        return merged;
    }

    private ServiceProperties serviceProperties(String service) {
        ServiceProperties properties = services.get(service);
        if (properties == null) {
            throw new IllegalArgumentException("Unknown service '%s'. Configured under forge.http.services: [%s]"
                    .formatted(service, services.keySet().stream().sorted().collect(Collectors.joining(", "))));
        }
        return properties;
    }

    /** One downstream service in a multi-service landscape. */
    public record ServiceProperties(String baseUrl, Map<String, String> headers) {

        public ServiceProperties {
            headers = Map.copyOf(headers == null ? Map.of() : headers);
        }
    }

    /**
     * Where the scenario's mock scope id is written into outgoing requests.
     * {@code jsonPath} is left null here on purpose: the auto-configuration
     * falls back to {@code forge.mock.scope-json-path} so the two modules
     * cannot drift apart.
     */
    public record ScopeProperties(Boolean enabled, String jsonPath, String header) {

        public ScopeProperties {
            if (enabled == null) {
                enabled = true;
            }
        }
    }

    /** Per-scenario request id header. */
    public record CorrelationProperties(Boolean enabled, String header) {

        public CorrelationProperties {
            if (enabled == null) {
                enabled = true;
            }
            if (header == null || header.isBlank()) {
                header = "X-Request-Id";
            }
        }
    }

    /** What ends up in the {@code forge.http} logger, and what never does. */
    public record LoggingProperties(
            Boolean enabled,
            Boolean bodies,
            Integer maxBodyChars,
            List<String> redactHeaders,
            List<String> redactJsonFields) {

        public LoggingProperties {
            if (enabled == null) {
                enabled = true;
            }
            if (bodies == null) {
                bodies = true;
            }
            if (maxBodyChars == null || maxBodyChars <= 0) {
                maxBodyChars = 2000;
            }
            redactHeaders = lowercase(redactHeaders, List.of(
                    "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "api-key"));
            redactJsonFields = lowercase(redactJsonFields, List.of(
                    "password", "secret", "token", "access_token", "refresh_token", "client_secret", "authorization"));
        }

        private static List<String> lowercase(List<String> configured, List<String> fallback) {
            List<String> source = configured == null ? fallback : configured;
            return source.stream().map(value -> value.toLowerCase(Locale.ROOT)).distinct().toList();
        }
    }

    /**
     * Retrying an infrastructure hiccup is useful; retrying a failing service
     * hides bugs. Disabled by default, and never applied to methods that are
     * not safe to repeat.
     */
    public record RetryProperties(
            Boolean enabled,
            Duration timeout,
            Duration delay,
            Set<Integer> statuses,
            Set<String> methods) {

        public RetryProperties {
            if (enabled == null) {
                enabled = false;
            }
            if (timeout == null) {
                timeout = Duration.ofSeconds(10);
            }
            if (delay == null) {
                delay = Duration.ofMillis(500);
            }
            statuses = Set.copyOf(statuses == null ? Set.of(502, 503, 504) : statuses);
            Set<String> configuredMethods = methods == null ? Set.of("GET", "HEAD", "OPTIONS") : methods;
            methods = configuredMethods.stream()
                    .map(method -> method.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
