package io.testforge.api.discovery;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class OpenApiSpecParser {

    private final OpenAPIV3Parser parser;

    public OpenApiSpecParser() {
        this(new OpenAPIV3Parser());
    }

    OpenApiSpecParser(OpenAPIV3Parser parser) {
        this.parser = parser;
    }

    public OpenAPI parse(ApiSpecSource source) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);

        SwaggerParseResult result;
        try {
            result = parseLocation(source.location(), options);
        } catch (RuntimeException e) {
            throw new ApiDiscoveryParseException(source, "Failed to parse OpenAPI spec: " + e.getMessage());
        }
        if (result == null || result.getOpenAPI() == null) {
            String messages = result == null ? "parser returned no result" : String.join("; ", result.getMessages());
            throw new ApiDiscoveryParseException(source, "Failed to parse OpenAPI spec: " + messages);
        }
        return result.getOpenAPI();
    }

    private SwaggerParseResult parseLocation(String location, ParseOptions options) {
        if (location.startsWith("classpath:")) {
            return parser.readContents(readClasspath(location), null, options);
        }
        if (location.startsWith("file:")) {
            return parser.readContents(readFile(filePath(location)), null, options);
        }
        if (location.startsWith("http://") || location.startsWith("https://")) {
            return parser.readLocation(location, null, options);
        }
        return parser.readContents(readFile(Path.of(location)), null, options);
    }

    private String readClasspath(String location) {
        String resourceName = location.substring("classpath:".length());
        if (resourceName.startsWith("/")) {
            resourceName = resourceName.substring(1);
        }
        try (java.io.InputStream in = Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new IllegalArgumentException("OpenAPI resource not found: " + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read " + location, e);
        }
    }

    private Path filePath(String location) {
        if (location.startsWith("file://")) {
            return Path.of(URI.create(location));
        }
        return Path.of(location.substring("file:".length()));
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read OpenAPI spec " + path, e);
        }
    }
}
