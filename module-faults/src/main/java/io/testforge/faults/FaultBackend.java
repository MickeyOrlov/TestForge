package io.testforge.faults;

import java.io.IOException;

/** Test seam over the upstream client, not a proxy implementation. */
public interface FaultBackend extends AutoCloseable {
    Handle create(String id, String upstream) throws IOException;
    interface Handle {
        Endpoint endpoint();
        void apply(String kind, long value) throws IOException;
        void delete() throws IOException;
    }
    record Endpoint(String host, int port) {
        public String address() { return host + ":" + port; }
    }
    @Override void close();
}
