package io.testforge.api.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.api.discovery.ApiDiscoveryProperties;
import io.testforge.api.discovery.ApiSpecSource;
import io.testforge.api.discovery.OpenApiSpecParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ApiCodegenRunner {

    private final OpenApiSpecParser parser;
    private final OpenApiJavaCodeGenerator generator;
    private final ObjectMapper objectMapper;
    private final ApiDiscoveryProperties discoveryProperties;
    private final ApiCodegenProperties properties;

    public ApiCodegenRunner(
            OpenApiSpecParser parser,
            OpenApiJavaCodeGenerator generator,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties discoveryProperties,
            ApiCodegenProperties properties) {
        this.parser = parser;
        this.generator = generator;
        this.objectMapper = objectMapper;
        this.discoveryProperties = discoveryProperties;
        this.properties = properties;
    }

    public ApiCodegenReport run() {
        Path outputDir = Path.of(properties.outputDir()).toAbsolutePath().normalize();
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        String configurationError = null;
        List<ApiCodegenSpecReport> specReports = List.of();
        if (properties.enabled()) {
            if (discoveryProperties.specs().isEmpty()) {
                configurationError = "No OpenAPI specs configured under forge.api-discovery.specs";
            } else {
                specReports = generateSpecs(outputDir);
            }
        }
        boolean healthy = configurationError == null && specReports.stream().noneMatch(ApiCodegenSpecReport::failed);
        ApiCodegenReport report = new ApiCodegenReport(
                properties.enabled(),
                Instant.now().toString(),
                healthy,
                outputDir.toString(),
                specReports,
                configurationError,
                reportJson.toString(),
                reportMarkdown.toString());
        writeReports(report, reportJson, reportMarkdown);
        return report;
    }

    public ApiCodegenReport assertGenerated() {
        ApiCodegenReport report = run();
        if (!report.healthy()) {
            throw new ApiCodegenException(report);
        }
        return report;
    }

    private List<ApiCodegenSpecReport> generateSpecs(Path outputDir) {
        List<ApiCodegenSpecReport> reports = new ArrayList<>();
        Map<String, String> outputOwners = new HashMap<>();
        Map<String, String> packageOwners = new HashMap<>();
        discoveryProperties.specs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    ApiSpecSource source = new ApiSpecSource(entry.getKey(), entry.getValue().location());
                    String outputName = safeName(source.id());
                    String packageName = JavaNames.packageSegment(source.id());
                    String outputOwner = outputOwners.putIfAbsent(outputName, source.id());
                    String packageOwner = packageOwners.putIfAbsent(packageName, source.id());
                    if (outputOwner != null || packageOwner != null) {
                        reports.add(collisionReport(source, outputDir, outputName, outputOwner, packageOwner));
                    } else {
                        reports.add(generateSpec(source, outputDir, outputName));
                    }
                });
        return List.copyOf(reports);
    }

    private ApiCodegenSpecReport generateSpec(ApiSpecSource source, Path outputDir, String outputName) {
        Path specRoot = outputDir.resolve(outputName);
        Path sourceRoot = specRoot.resolve("src/main/java");
        try {
            replaceDirectory(specRoot);
            OpenAPI openApi = parser.parse(source);
            GeneratedApiSources generated = generator.generate(source.id(), openApi, properties.basePackage());

            List<String> files = new ArrayList<>();
            for (GeneratedSource generatedSource : generated.sources()) {
                Path file = sourceRoot.resolve(generatedSource.relativePath()).normalize();
                if (!file.startsWith(sourceRoot)) {
                    throw new IllegalStateException("Generated path escapes source root: "
                            + generatedSource.relativePath());
                }
                writeString(file, generatedSource.content());
                files.add(file.toString());
            }
            return new ApiCodegenSpecReport(
                    source.id(),
                    source.location(),
                    false,
                    generated.modelCount(),
                    generated.clientCount(),
                    generated.operationCount(),
                    sourceRoot.toString(),
                    files,
                    generated.warnings(),
                    null);
        } catch (RuntimeException e) {
            return new ApiCodegenSpecReport(
                    source.id(),
                    source.location(),
                    true,
                    0,
                    0,
                    0,
                    sourceRoot.toString(),
                    List.of(),
                    List.of(),
                    e.getMessage());
        }
    }

    private ApiCodegenSpecReport collisionReport(
            ApiSpecSource source,
            Path outputDir,
            String outputName,
            String outputOwner,
            String packageOwner) {
        List<String> collisions = new ArrayList<>();
        if (outputOwner != null) {
            collisions.add("generated directory '" + outputName + "' already belongs to spec '" + outputOwner + "'");
        }
        if (packageOwner != null) {
            collisions.add("Java package suffix '" + JavaNames.packageSegment(source.id())
                    + "' already belongs to spec '" + packageOwner + "'");
        }
        Path sourceRoot = outputDir.resolve(outputName).resolve("src/main/java");
        return new ApiCodegenSpecReport(
                source.id(),
                source.location(),
                true,
                0,
                0,
                0,
                sourceRoot.toString(),
                List.of(),
                List.of(),
                "OpenAPI spec id collision: " + String.join("; ", collisions));
    }

    private void replaceDirectory(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(this::delete);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to clean generated directory " + directory, e);
        }
    }

    private void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete generated path " + path, e);
        }
    }

    private void writeReports(ApiCodegenReport report, Path reportJson, Path reportMarkdown) {
        try {
            Files.createDirectories(reportJson.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportJson.toFile(), report);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + reportJson, e);
        }
        writeString(reportMarkdown, markdown(report));
    }

    private String markdown(ApiCodegenReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# API Code Generation Report\n\n")
                .append("- enabled: ").append(report.enabled()).append('\n')
                .append("- healthy: ").append(report.healthy()).append('\n')
                .append("- generatedAt: ").append(report.generatedAt()).append('\n')
                .append("- specs: ").append(report.specs().size()).append('\n');
        if (report.error() != null) {
            out.append("- error: ").append(report.error()).append('\n');
        }
        out.append('\n');

        for (ApiCodegenSpecReport spec : report.specs()) {
            out.append("## ").append(spec.specId()).append("\n\n")
                    .append("- status: ").append(spec.failed() ? "FAILED" : "OK").append('\n')
                    .append("- location: ").append(spec.location()).append('\n')
                    .append("- models: ").append(spec.models()).append('\n')
                    .append("- clients: ").append(spec.clients()).append('\n')
                    .append("- operations: ").append(spec.operations()).append('\n')
                    .append("- sourceRoot: ").append(spec.sourceRoot()).append('\n')
                    .append("- files: ").append(spec.files().size()).append('\n');
            if (spec.error() != null) {
                out.append("- error: ").append(spec.error()).append('\n');
            }
            if (!spec.warnings().isEmpty()) {
                out.append("- warnings:\n");
                spec.warnings().forEach(warning -> out.append("  - ").append(warning).append('\n'));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private void writeString(Path path, String value) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, value, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
    }

    private String safeName(String name) {
        String safe = name.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "api-spec" : safe;
    }
}
