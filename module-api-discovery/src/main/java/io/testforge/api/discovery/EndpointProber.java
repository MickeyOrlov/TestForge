package io.testforge.api.discovery;

import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.testforge.api.discovery.ApiDiscoveryProperties.ProbeProperties;
import io.testforge.http.ApiClient;
import java.nio.charset.StandardCharsets;

/**
 * Sends one request per endpoint through {@link ApiClient}, so authentication,
 * correlation ids and redacted logging are whatever the project already
 * configured for {@code module-http}.
 *
 * <p>Two things this deliberately never does: it never sends a request body,
 * for any method, and it never runs requests concurrently. A discovery run is
 * a guest in somebody's environment.
 */
public class EndpointProber {

    private final ApiClient apiClient;
    private final String service;
    private final ProbeProperties properties;

    public EndpointProber(ApiClient apiClient, String service, ProbeProperties properties) {
        this.apiClient = apiClient;
        this.service = service;
        this.properties = properties;
    }

    public ResponseSample probe(EndpointDescriptor endpoint, ResolvedParameters parameters) {
        long startedAt = System.nanoTime();
        try {
            RequestSpecification request = apiClient.request(service);
            parameters.path().forEach(request::pathParam);
            parameters.query().forEach(request::queryParam);

            Response response = request.request(endpoint.method(), endpoint.path());
            return sample(response, millisSince(startedAt));
        } catch (RuntimeException e) {
            return ResponseSample.failed(e.toString(), millisSince(startedAt));
        }
    }

    private ResponseSample sample(Response response, long durationMillis) {
        String body = response.asString();
        long size = body == null ? 0 : body.getBytes(StandardCharsets.UTF_8).length;

        if (size > properties.maxResponseBytes()) {
            return new ResponseSample(response.getStatusCode(), response.getContentType(), null, durationMillis,
                    "response of %d bytes exceeds probe.max-response-bytes (%d)"
                            .formatted(size, properties.maxResponseBytes()));
        }
        return new ResponseSample(response.getStatusCode(), response.getContentType(), body, durationMillis, null);
    }

    private long millisSince(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
