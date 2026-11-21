package io.testforge.faults;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Exact upstream allowlisting; container/network startup is always explicit and lazy. */
@ConfigurationProperties("forge.faults")
public record FaultsProperties(boolean enabled, String serverUrl, String dataHost, String dockerNetwork,
        boolean externalCleanupAcknowledged, int firstPort, int slots, Map<String, String> proxies,
        List<String> allowedUpstreams) {
    public FaultsProperties {
        firstPort = firstPort == 0 ? 8666 : firstPort;
        slots = slots == 0 ? 16 : slots;
        proxies = Map.copyOf(proxies == null ? Map.of() : proxies);
        allowedUpstreams = List.copyOf(allowedUpstreams == null ? List.of() : allowedUpstreams);
        if (slots < 1 || slots > 32 || firstPort < 1024 || firstPort + slots > 65536)
            throw new IllegalArgumentException("Invalid faults port pool");
        if (serverUrl == null && (firstPort != 8666 || slots > 32))
            throw new IllegalArgumentException("Managed Toxiproxy uses ports starting at 8666");
        proxies.forEach((name, upstream) -> {
            if (!name.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid proxy name");
            validateUpstream(upstream);
        });
        allowedUpstreams.forEach(FaultsProperties::validateUpstream);
        if (serverUrl != null) {
            URI uri = URI.create(serverUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException("Use an explicit HTTP(S) Toxiproxy control origin without credentials");
        }
        if (dataHost != null && !dataHost.matches("[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("Invalid proxy data host");
    }
    private static void validateUpstream(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.-]+:[0-9]{1,5}"))
            throw new IllegalArgumentException("Upstreams must be host:port without credentials or paths");
        int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid upstream port");
    }
    String upstream(String name) {
        if (!enabled) throw new IllegalStateException("Set forge.faults.enabled=true");
        String upstream = proxies.get(name);
        if (upstream == null || !allowedUpstreams.contains(upstream))
            throw new IllegalArgumentException("Unknown or non-allowlisted proxy");
        checkUpstream(upstream);
        return upstream;
    }
    void checkUpstream(String upstream) {
        if (!enabled) throw new IllegalStateException("Set forge.faults.enabled=true");
        if (!allowedUpstreams.contains(upstream)) throw new IllegalArgumentException("Upstream is not allowlisted");
        if (serverUrl != null && !externalCleanupAcknowledged)
            throw new IllegalStateException("External Toxiproxy requires external-cleanup-acknowledged=true");
    }
}
