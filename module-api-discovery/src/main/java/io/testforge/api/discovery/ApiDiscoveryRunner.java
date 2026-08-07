package io.testforge.api.discovery;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class ApiDiscoveryRunner {

    private final OpenApiSpecParser parser;
    private final EndpointCatalogBuilder catalogBuilder;
    private final OpenApiShapeNormalizer normalizer;
    private final ObjectMapper objectMapper;
    private final ApiDiscoveryProperties properties;

    public ApiDiscoveryRunner(
            OpenApiSpecParser parser,
            EndpointCatalogBuilder catalogBuilder,
            OpenApiShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ApiDiscoveryProperties properties) {
        this.parser = parser;
        this.catalogBuilder = catalogBuilder;
        this.normalizer = normalizer;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public ApiDiscoveryReport run() {
        Path outputDir = Path.of(properties.outputDir());
        Path reportJson = outputDir.resolve("report.json");
        Path reportMarkdown = outputDir.resolve("report.md");

        List<ApiSpecReport> specReports = properties.enabled()
                ? runSpecs(outputDir)
                : List.of();
        boolean healthy = specReports.stream().noneMatch(ApiSpecReport::failed);

        ApiDiscoveryReport report = new ApiDiscoveryReport(
                properties.enabled(),
                Instant.now().toString(),
                healthy,
                specReports,
                outputDir.toString(),
                reportJson.toString(),
                reportMarkdown.toString());
        writeReports(report, reportJson, reportMarkdown);
        return report;
    }

    public ApiDiscoveryReport assertHealthy() {
        ApiDiscoveryReport report = run();
        if (!report.healthy()) {
            throw new ApiDiscoveryException(report);
        }
        return report;
    }

    private List<ApiSpecReport> runSpecs(Path outputDir) {
        List<ApiSpecReport> reports = new java.util.ArrayList<>();
        for (ApiSpecSource source : sources()) {
            reports.add(runSpec(source, outputDir));
        }
        return List.copyOf(reports);
    }

    private List<ApiSpecSource> sources() {
        return properties.specs().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new ApiSpecSource(entry.getKey(), entry.getValue().location()))
                .toList();
    }

    private ApiSpecReport runSpec(ApiSpecSource source, Path outputDir) {
        String safeSpecId = safeName(source.id());
        Path specDir = outputDir.resolve(safeSpecId);
        Path catalogArtifact = specDir.resolve("catalog.json");
        try {
            OpenAPI openApi = parser.parse(source);
            EndpointCatalog catalog = catalogBuilder.build(source.id(), openApi);
            List<ApiSchemaShape> shapes = normalizer.normalize(catalog, openApi);

            writeJson(catalogArtifact, catalog);
            CatalogDiff catalogDiff = diffCatalog(safeSpecId, catalog);
            List<ApiShapeReport> shapeReports = writeAndDiffShapes(safeSpecId, specDir, shapes);

            boolean failed = (!catalogDiff.empty() && properties.failOnCatalogDiff())
                    || shapeReports.stream().anyMatch(ApiShapeReport::failed);
            return new ApiSpecReport(
                    source.id(),
                    source.location(),
                    failed,
                    catalog.endpoints().size(),
                    catalogDiff,
                    shapeReports,
                    catalogArtifact.toString(),
                    null);
        } catch (RuntimeException e) {
            return new ApiSpecReport(
                    source.id(),
                    source.location(),
                    true,
                    0,
                    CatalogDiff.noBaseline(),
                    List.of(),
                    catalogArtifact.toString(),
                    e.getMessage());
        }
    }

    private CatalogDiff diffCatalog(String safeSpecId, EndpointCatalog current) {
        Path baseline = Path.of(properties.baselineDir()).resolve(safeSpecId).resolve("catalog.json");
        if (!Files.exists(baseline)) {
            return CatalogDiff.noBaseline();
        }
        try {
            EndpointCatalog baselineCatalog = objectMapper.readValue(baseline.toFile(), EndpointCatalog.class);
            return CatalogDiff.between(baselineCatalog, current);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read baseline catalog " + baseline, e);
        }
    }

    private List<ApiShapeReport> writeAndDiffShapes(String safeSpecId, Path specDir, List<ApiSchemaShape> shapes) {
        Path shapesDir = specDir.resolve("shapes");
        List<ApiShapeReport> reports = new java.util.ArrayList<>();
        for (ApiSchemaShape shape : shapes) {
            Path shapeArtifact = shapesDir.resolve(shape.name());
            writeJson(shapeArtifact, shape);
            ApiShapeDiff diff = diffShape(safeSpecId, shape);
            boolean failed = !diff.empty() && properties.failOnShapeDiff();
            reports.add(new ApiShapeReport(
                    shape.name(),
                    failed,
                    shape.operationKey(),
                    shape.direction(),
                    shape.statusCode(),
                    shape.contentType(),
                    diff,
                    shapeArtifact.toString()));
        }
        return List.copyOf(reports);
    }

    private ApiShapeDiff diffShape(String safeSpecId, ApiSchemaShape current) {
        Path baseline = Path.of(properties.baselineDir())
                .resolve(safeSpecId)
                .resolve("shapes")
                .resolve(current.name());
        if (!Files.exists(baseline)) {
            return ApiShapeDiff.noBaseline();
        }
        try {
            ApiSchemaShape baselineShape = objectMapper.readValue(baseline.toFile(), ApiSchemaShape.class);
            return ApiShapeDiff.between(baselineShape, current);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read baseline shape " + baseline, e);
        }
    }

    private void writeReports(ApiDiscoveryReport report, Path reportJson, Path reportMarkdown) {
        writeJson(reportJson, report);
        writeString(reportMarkdown, markdown(report));
    }

    private String markdown(ApiDiscoveryReport report) {
        StringBuilder out = new StringBuilder();
        out.append("# API Discovery Report\n\n");
        out.append("- enabled: ").append(report.enabled()).append('\n');
        out.append("- healthy: ").append(report.healthy()).append('\n');
        out.append("- generatedAt: ").append(report.generatedAt()).append('\n');
        out.append("- specs: ").append(report.specs().size()).append("\n\n");

        for (ApiSpecReport spec : report.specs()) {
            out.append("## ").append(spec.specId()).append("\n\n");
            out.append("- status: ").append(spec.failed() ? "FAILED" : "OK").append('\n');
            out.append("- location: ").append(spec.location()).append('\n');
            out.append("- endpoints: ").append(spec.endpoints()).append('\n');
            if (spec.error() != null) {
                out.append("- error: ").append(spec.error()).append('\n');
            }
            appendCatalogDiff(out, spec.catalogDiff());
            appendShapeDiffs(out, spec.shapes());
            out.append('\n');
        }
        return out.toString();
    }

    private void appendCatalogDiff(StringBuilder out, CatalogDiff diff) {
        if (!diff.baselinePresent()) {
            out.append("- catalogDiff: baseline missing\n");
            return;
        }
        if (diff.empty()) {
            out.append("- catalogDiff: none\n");
            return;
        }
        out.append("- catalogDiff:\n");
        diff.added().forEach((key, endpoint) -> out.append("  - added ").append(key).append('\n'));
        diff.removed().forEach((key, endpoint) -> out.append("  - removed ").append(key).append('\n'));
        diff.changed().forEach(change -> out.append("  - changed ").append(change.key()).append('\n'));
    }

    private void appendShapeDiffs(StringBuilder out, List<ApiShapeReport> shapes) {
        long changedShapes = shapes.stream()
                .filter(shape -> shape.shapeDiff().baselinePresent() && !shape.shapeDiff().empty())
                .count();
        out.append("- shapes: ").append(shapes.size()).append('\n');
        if (changedShapes == 0) {
            return;
        }
        out.append("- shapeDiffs:\n");
        for (ApiShapeReport shape : shapes) {
            ApiShapeDiff diff = shape.shapeDiff();
            if (!diff.baselinePresent() || diff.empty()) {
                continue;
            }
            out.append("  - ").append(shape.name()).append('\n');
            diff.added().forEach((path, entry) -> out.append("    - added ")
                    .append(path)
                    .append(": ")
                    .append(describe(entry))
                    .append('\n'));
            diff.removed().forEach((path, entry) -> out.append("    - removed ")
                    .append(path)
                    .append(": ")
                    .append(describe(entry))
                    .append('\n'));
            diff.changed().forEach(change -> out.append("    - changed ")
                    .append(change.path())
                    .append(": ")
                    .append(describe(change.baseline()))
                    .append(" -> ")
                    .append(describe(change.current()))
                    .append('\n'));
        }
    }

    private String describe(SchemaShapeEntry entry) {
        return entry.type()
                + " required="
                + entry.required()
                + " nullable="
                + entry.nullable();
    }

    private void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + path, e);
        }
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
        String safe = name.toLowerCase()
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "api-spec" : safe;
    }
}
