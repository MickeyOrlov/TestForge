package io.testforge.faults;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Latency;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Only this class knows Testcontainers and the upstream Java client. */
public final class ToxiproxyBackend implements FaultBackend {
    public static final String IMAGE = image();
    private static String image() {
        try (var stream = ToxiproxyBackend.class.getResourceAsStream("versions.properties")) {
            var versions = new java.util.Properties(); versions.load(stream); return versions.getProperty("image");
        } catch (IOException e) { throw new IllegalStateException("Missing pinned Toxiproxy image"); }
    }
    private final FaultsProperties properties;
    private final Set<Integer> reserved = new HashSet<>();
    private final Set<String> owned = new HashSet<>();
    private ToxiproxyContainer container;
    private ToxiproxyClient client;
    private boolean closed;

    public ToxiproxyBackend(FaultsProperties properties) { this.properties = properties; }

    private void start() {
        if (client != null) return;
        if (properties.serverUrl() == null) {
            container = new ToxiproxyContainer(IMAGE);
            if (properties.dockerNetwork() != null) container.withNetworkMode(properties.dockerNetwork());
            try { container.start(); }
            catch (RuntimeException e) { container.close(); container = null; throw new IllegalStateException("Toxiproxy startup failed"); }
            client = new ToxiproxyClient(container.getHost(), container.getControlPort());
        } else {
            URI uri = URI.create(properties.serverUrl());
            client = new ToxiproxyClient(uri.getScheme(), uri.getHost(), uri.getPort());
        }
    }
    @Override public synchronized Handle create(String id, String upstream) throws IOException {
        if (closed) throw new IllegalStateException("Fault backend is closed");
        properties.checkUpstream(upstream);
        if (!id.matches("tf-[a-z0-9-]{1,64}")) throw new IllegalArgumentException("Use an owned fault proxy ID");
        start();
        int port = java.util.stream.IntStream.range(properties.firstPort(), properties.firstPort() + properties.slots())
                .filter(p -> !reserved.contains(p)).findFirst().orElseThrow(() -> new IllegalStateException("Fault proxy pool exhausted"));
        reserved.add(port);
        // On uncertain create/delete failure quarantine the port until this controller closes.
        owned.add(id);
        Proxy proxy = client.createProxy(id, "0.0.0.0:" + port, upstream);
        String host = properties.dataHost() != null ? properties.dataHost()
                : container != null ? container.getHost() : URI.create(properties.serverUrl()).getHost();
        Endpoint endpoint = new Endpoint(host, container == null ? port : container.getMappedPort(port));
        return new Handle() {
            private Latency latency;
            @Override public Endpoint endpoint() { return endpoint; }
            @Override public void apply(String kind, long value) throws IOException {
                String toxic = "tf-" + UUID.randomUUID();
                switch (kind) {
                    case "latency" -> latency = proxy.toxics().latency(toxic, ToxicDirection.DOWNSTREAM, value);
                    case "jitter" -> {
                        if (latency == null) throw new IllegalStateException("Set latency before jitter");
                        latency.setJitter(value);
                    }
                    case "timeout" -> proxy.toxics().timeout(toxic, ToxicDirection.DOWNSTREAM, value);
                    case "reset_peer" -> proxy.toxics().resetPeer(toxic, ToxicDirection.DOWNSTREAM, value);
                    case "bandwidth" -> proxy.toxics().bandwidth(toxic, ToxicDirection.DOWNSTREAM, value);
                    case "down" -> proxy.disable();
                    case "restore" -> {
                        for (var existing : proxy.toxics().getAll()) existing.remove();
                        proxy.enable(); latency = null;
                    }
                    default -> throw new IllegalArgumentException("Unknown toxic");
                }
            }
            @Override public void delete() throws IOException {
                proxy.delete();
                synchronized (ToxiproxyBackend.this) { reserved.remove(port); owned.remove(id); }
            }
        };
    }
    @Override public synchronized void close() {
        closed = true;
        if (container != null) { container.close(); owned.clear(); }
        else if (client != null && !owned.isEmpty()) {
            try {
                for (Proxy proxy : client.getProxies()) {
                    if (owned.contains(proxy.getName())) { proxy.delete(); owned.remove(proxy.getName()); }
                }
                owned.clear();
            } catch (IOException | RuntimeException e) { throw new IllegalStateException("External owned proxy cleanup failed"); }
        }
    }
}
