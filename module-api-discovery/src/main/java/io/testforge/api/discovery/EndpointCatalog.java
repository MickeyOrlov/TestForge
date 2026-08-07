package io.testforge.api.discovery;

import java.util.List;

/**
 * Every endpoint the document describes, in a stable order.
 *
 * <p>Serialized as {@code catalog.json} on every run, including runs that probe
 * nothing — the catalog is the artifact a team reads first when it does not yet
 * know the API.
 *
 * <p>{@code baseUrl} is recorded on purpose: a discovery run pointed at the
 * wrong environment should be obvious from the artifact, not from the logs of
 * whoever ran it.
 */
public record EndpointCatalog(
        String openapi,
        String title,
        String version,
        String source,
        String baseUrl,
        List<EndpointDescriptor> endpoints) {

    public EndpointCatalog {
        endpoints = List.copyOf(endpoints == null ? List.of() : endpoints);
    }
}
