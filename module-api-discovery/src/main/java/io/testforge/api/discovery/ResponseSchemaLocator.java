package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Optional;

/**
 * Finds the schema the document declares for the response that actually came
 * back.
 *
 * <p>Falls back from the exact status to {@code default}, and accepts any JSON
 * media type ({@code application/json}, {@code application/hal+json},
 * {@code application/problem+json}). An operation with no declared schema is
 * reported as such — informational, never a failure. Half the value of a
 * discovery run is finding out which responses the document says nothing about.
 */
public class ResponseSchemaLocator {

    public Optional<LocatedSchema> locate(OpenApiDocument document, EndpointDescriptor endpoint,
                                          int status, String contentType) {

        JsonNode responses = endpoint.operation().path("responses");
        JsonNode response = responses.path(String.valueOf(status));
        if (!response.isObject()) {
            response = responses.path("default");
        }
        if (!response.isObject()) {
            return Optional.empty();
        }

        JsonNode content = document.dereference(response).path("content");
        if (!content.isObject()) {
            return Optional.empty();
        }

        JsonNode media = mediaType(content, contentType);
        if (media == null) {
            return Optional.empty();
        }

        JsonNode schema = media.path("schema");
        if (!schema.isObject()) {
            return Optional.empty();
        }

        String ref = schema.path("$ref").asText(null);
        return Optional.of(new LocatedSchema(schema, ref != null ? ref : "inline"));
    }

    private JsonNode mediaType(JsonNode content, String contentType) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);

        // the response's own media type first, then any JSON entry the
        // document declares
        for (var entry : content.properties()) {
            if (!normalized.isBlank() && normalized.startsWith(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        for (var entry : content.properties()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).contains("json")) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** The schema node plus where it came from, for the report. */
    public record LocatedSchema(JsonNode schema, String ref) {
    }
}
