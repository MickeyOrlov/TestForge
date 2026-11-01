package io.testforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A finished run's {@code manifest.json}, as {@code module-reporting} writes
 * it: what each module published, and the problems reporting itself had.
 * Read as plain JSON — this module does not depend on module-reporting.
 */
record RunIndex(String runId, Path manifest, List<Artifact> artifacts, List<String> reportingProblems) {

    record Artifact(String source, String category, String name, String path, Map<String, String> metadata) {
    }

    static final String MANIFEST = "manifest.json";

    /** The requested run, or the newest one that has a manifest; empty when there is none. */
    static Optional<RunIndex> find(Path root, String runId, ObjectMapper mapper) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        Path manifest;
        if (runId != null) {
            manifest = root.resolve(runId).resolve(MANIFEST);
            if (!Files.isRegularFile(manifest)) {
                throw new QualityException("Run " + runId + " has no " + MANIFEST + " under " + root);
            }
        } else {
            try (Stream<Path> runs = Files.list(root)) {
                // run ids sort by time (module-reporting's ArtifactRunLayout)
                Optional<Path> newest = runs.filter(run -> Files.isRegularFile(run.resolve(MANIFEST)))
                        .max(Comparator.comparing(run -> run.getFileName().toString()));
                if (newest.isEmpty()) {
                    return Optional.empty();
                }
                manifest = newest.get().resolve(MANIFEST);
            } catch (IOException e) {
                throw new QualityException("Could not list runs under " + root, e);
            }
        }
        return Optional.of(read(manifest, mapper));
    }

    private static RunIndex read(Path manifest, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(manifest.toFile());
        } catch (IOException e) {
            throw new QualityException("Could not read " + manifest, e);
        }
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonNode node : root.path("artifacts")) {
            Map<String, String> metadata = new LinkedHashMap<>();
            node.path("metadata").properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
            artifacts.add(new Artifact(node.path("source").asText(), node.path("category").asText(),
                    node.path("name").asText(), node.path("path").asText(), metadata));
        }
        List<String> problems = new ArrayList<>();
        root.path("reportingProblems").forEach(problem -> problems.add(problem.asText()));
        return new RunIndex(root.path("runId").asText(manifest.getParent().getFileName().toString()), manifest,
                List.copyOf(artifacts), List.copyOf(problems));
    }

    /**
     * Where an artifact's file is: its {@code location} metadata (files kept
     * outside the run directory), else its path inside the run directory.
     */
    Optional<Path> file(Artifact artifact) {
        String location = artifact.metadata().get("location");
        Path file = location != null && !location.isBlank() ? Path.of(location)
                : artifact.path() == null || artifact.path().isBlank() ? null
                : manifest.getParent().resolve(artifact.path());
        return file != null && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    List<Artifact> artifacts(String source, String category) {
        return artifacts.stream().filter(a -> a.source().equals(source) && a.category().equals(category)).toList();
    }
}
