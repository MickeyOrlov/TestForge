package io.testforge.load;

import java.net.URI;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/** Optional native k6 remote-write output; no exporter, metrics store or dashboard server is implemented here. */
public record PrometheusProperties(boolean enabled, String remoteWriteUrl, String bearerToken,
                                   String project, String environment, String grafanaUrl, boolean runIds) {
    @ConstructorBinding
    public PrometheusProperties {
        remoteWriteUrl = remoteWriteUrl == null || remoteWriteUrl.isBlank()
                ? "http://127.0.0.1:9090/api/v1/write" : remoteWriteUrl;
        project = project == null || project.isBlank() ? "testforge" : project;
        environment = environment == null || environment.isBlank() ? "local" : environment;
        grafanaUrl = grafanaUrl == null || grafanaUrl.isBlank() ? "http://127.0.0.1:3000" : grafanaUrl;
        if (!project.matches("[A-Za-z0-9_.-]{1,64}") || !environment.matches("[A-Za-z0-9_.-]{1,64}")) {
            throw new IllegalArgumentException("Load project/environment labels must be 1–64 letters, digits, _, . or -");
        }
        if (enabled) {
            validateUrl(remoteWriteUrl);
            validateUrl(grafanaUrl);
        }
    }

    public PrometheusProperties(boolean enabled, String remoteWriteUrl, String bearerToken,
                                String project, String environment, String grafanaUrl) {
        this(enabled, remoteWriteUrl, bearerToken, project, environment, grafanaUrl, false);
    }

    private static void validateUrl(String value) {
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Load observability URLs must be HTTP(S), without credentials/query/fragment");
        }
    }

    @Override
    public String toString() {
        return "PrometheusProperties[enabled=" + enabled + ", credentials=<redacted>]";
    }
}
