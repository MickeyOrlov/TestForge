package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.restassured.response.Response;
import io.testforge.http.ApiClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the OpenAPI document from wherever the project keeps it.
 *
 * <p>Supported sources:
 *
 * <ul>
 *   <li>{@code path:/v3/api-docs} — fetched through {@link ApiClient}, so a
 *       document behind authentication needs no extra configuration;</li>
 *   <li>{@code https://host/openapi.json} — also through {@link ApiClient};</li>
 *   <li>{@code classpath:openapi/orders.yaml};</li>
 *   <li>{@code file:/path/to/openapi.json}.</li>
 * </ul>
 *
 * <p>JSON and YAML are both accepted, detected by the first non-blank
 * character rather than by extension.
 */
public class OpenApiReader {

    private static final Logger log = LoggerFactory.getLogger(OpenApiReader.class);

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final ApiClient apiClient;
    private final String service;

    public OpenApiReader(ObjectMapper jsonMapper, ApiClient apiClient, String service) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new YAMLMapper();
        this.apiClient = apiClient;
        this.service = service;
    }

    public OpenApiDocument read(String source) {
        String content = load(source);
        JsonNode root = parse(content, source);

        String openapi = root.path("openapi").asText(null);
        if (openapi == null || openapi.isBlank()) {
            String swagger = root.path("swagger").asText(null);
            throw new IllegalArgumentException(swagger != null
                    ? "%s is a Swagger %s document; convert it to OpenAPI 3 first".formatted(source, swagger)
                    : "%s has no 'openapi' field — it is not an OpenAPI 3 document".formatted(source));
        }
        if (!openapi.startsWith("3.")) {
            throw new IllegalArgumentException(
                    "%s declares OpenAPI %s; only 3.x is supported".formatted(source, openapi));
        }

        JsonNode info = root.path("info");
        OpenApiDocument document = new OpenApiDocument(
                openapi,
                info.path("title").asText("(untitled)"),
                info.path("version").asText("(no version)"),
                source,
                root);

        log.info("Read OpenAPI {} document '{}' {} from {} ({} paths)",
                openapi, document.title(), document.version(), source, document.paths().size());
        return document;
    }

    private String load(String source) {
        if (source.startsWith("classpath:")) {
            return fromClasspath(source.substring("classpath:".length()));
        }
        if (source.startsWith("file:")) {
            return fromFile(source.substring("file:".length()));
        }
        if (source.startsWith("path:")) {
            return fromHttp(source.substring("path:".length()), source);
        }
        if (source.startsWith("http://") || source.startsWith("https://")) {
            return fromHttp(source, source);
        }
        throw new IllegalArgumentException(
                "Unsupported forge.api-discovery.spec.source '%s'; expected classpath:, file:, path: or http(s)://"
                        .formatted(source));
    }

    private String fromClasspath(String location) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream stream = loader.getResourceAsStream(location)) {
            if (stream == null) {
                throw new IllegalArgumentException("OpenAPI document not found on the classpath: " + location);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read classpath OpenAPI document " + location, e);
        }
    }

    private String fromFile(String location) {
        try {
            return Files.readString(Path.of(location), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read OpenAPI document " + location, e);
        }
    }

    private String fromHttp(String target, String source) {
        Response response = apiClient.request(service).get(target);
        if (response.getStatusCode() != 200) {
            throw new IllegalStateException("Fetching the OpenAPI document from %s returned %d"
                    .formatted(source, response.getStatusCode()));
        }
        return response.asString();
    }

    private JsonNode parse(String content, String source) {
        String trimmed = content.stripLeading();
        ObjectMapper mapper = trimmed.startsWith("{") ? jsonMapper : yamlMapper;
        try {
            return mapper.readTree(content);
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to parse the OpenAPI document from %s: %s".formatted(source, e.getMessage()), e);
        }
    }
}
