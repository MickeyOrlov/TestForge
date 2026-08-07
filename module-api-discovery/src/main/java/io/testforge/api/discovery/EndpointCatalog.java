package io.testforge.api.discovery;

import java.util.List;

public record EndpointCatalog(String specId, List<ApiEndpoint> endpoints) {

    public EndpointCatalog {
        endpoints = List.copyOf(endpoints == null ? List.of() : endpoints);
    }
}
