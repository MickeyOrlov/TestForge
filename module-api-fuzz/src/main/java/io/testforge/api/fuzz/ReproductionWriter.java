package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.api.explorer.PreparedRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the folder an engineer actually opens.
 *
 * <p>A report line says a case failed. This says which request to send, what
 * the document promised, what came back, how many times it came back, and the
 * one configuration snippet that runs exactly this case again — and it does so
 * in a directory named after the case, so a link in a ticket keeps working.
 *
 * <p>No {@code curl}. The request went out through {@code ApiClient} with
 * whatever authentication, correlation and retry the project configured, and a
 * standalone command line that quietly drops all of that is worse than no
 * command line: it fails differently and sends the reader hunting for a bug in
 * the wrong place.
 */
public class ReproductionWriter {

    private final ObjectMapper objectMapper;

    public ReproductionWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(Path outputDir, FuzzObservation observation, ReproductionManifest manifest,
                        ControlResult control, PreparedRequest request) {

        Path directory = outputDir.resolve("reproductions").resolve(safeName(manifest.caseId()));
        writeJson(directory.resolve("manifest.json"), manifest);
        writeJson(directory.resolve("request.json"), request(observation, request));
        writeString(directory.resolve("reproduce.md"), markdown(observation, manifest, control));
        return directory.toString();
    }

    /** The minimal request, redacted, in the shape a reader can retype. */
    private Map<String, Object> request(FuzzObservation observation, PreparedRequest request) {
        Map<String, Object> shape = new LinkedHashMap<>();
        shape.put("method", request.method());
        shape.put("path", request.pathTemplate());
        shape.put("pathParameters", new TreeMap<>(request.pathParameters()));
        shape.put("queryParameters", new TreeMap<>(request.queryParameters()));
        shape.put("contentType", request.contentType());
        shape.put("body", observation.shrink().minimalBody() != null
                ? observation.shrink().minimalBody()
                : request.body());
        return shape;
    }

    private String markdown(FuzzObservation observation, ReproductionManifest manifest, ControlResult control) {
        FuzzCase fuzzCase = observation.fuzzCase();
        StringBuilder out = new StringBuilder("# ").append(fuzzCase.id()).append("\n\n");

        out.append("## 1. The case\n\n");
        out.append("- operation: `").append(fuzzCase.operationKey()).append("`\n");
        out.append("- target: `").append(fuzzCase.location()).append("`\n");
        out.append("- mutation: ").append(fuzzCase.kind()).append('\n');
        out.append("- constraint: ").append(fuzzCase.constraint() == null
                ? "none — this is a robustness probe, not a declared rule" : fuzzCase.constraint()).append('\n');
        out.append("- spec: ").append(manifest.specId())
                .append(" (").append(manifest.specFingerprint()).append(")\n");

        out.append("\n## 2. What was sent\n\n");
        out.append("- ").append(observation.requestFragment()).append('\n');
        out.append("- everything else in the request was the valid baseline\n");
        out.append("- full request: `request.json`\n");

        out.append("\n## 3. What the document promised\n\n");
        out.append(switch (fuzzCase.expectation()) {
            case REJECT -> "The schema forbids this value, so the service should refuse it (400 or 422).\n";
            case ACCEPT -> "The value is valid, if extreme, so the service should accept it.\n";
            case UNSPECIFIED -> "The document says nothing about this value; only a crash or an echo "
                    + "would be a defect.\n";
        });

        out.append("\n## 4. What the service answered\n\n");
        out.append("- control request: ").append(control.status() == null ? "-" : control.status())
                .append(' ').append(control.outcome()).append('\n');
        out.append("- this case: ").append(observation.status() == null ? "-" : observation.status()).append('\n');
        out.append("- verdict: **").append(observation.verdict()).append("**\n");
        observation.evidence().forEach(evidence ->
                out.append("- evidence: ").append(evidence.kind()).append(" — ")
                        .append(evidence.detail()).append('\n'));

        out.append("\n## 5. Is it stable\n\n");
        out.append("- ").append(observation.confirmation().summary()).append('\n');
        if (observation.confirmation().reason() != null) {
            out.append("- ").append(observation.confirmation().reason()).append('\n');
        }
        if (observation.flaky()) {
            out.append("- the response varied between identical requests; treat the count above as the "
                    + "evidence, not the label\n");
        }

        out.append("\n## 6. Is it minimized\n\n");
        out.append("- ").append(observation.shrink().summary()).append('\n');
        observation.shrink().removed().forEach(removed ->
                out.append("  - removed ").append(removed).append('\n'));

        out.append("\n## 7. Run it again\n\n");
        out.append("```yaml\nforge:\n  api-fuzz:\n    enabled: true\n    seed: ")
                .append(manifest.seed())
                .append("\n    only-cases:\n      - \"").append(fuzzCase.id()).append("\"\n```\n");
        out.append("\nThe request goes out through `ApiClient`, so authentication and any project "
                + "`ApiRequestCustomizer` apply exactly as they did here. There is deliberately no `curl` "
                + "equivalent: it would drop all of that and fail for a different reason.\n");

        return out.toString();
    }

    private String safeName(String name) {
        String safe = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return safe.isBlank() ? "case" : safe;
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
}
