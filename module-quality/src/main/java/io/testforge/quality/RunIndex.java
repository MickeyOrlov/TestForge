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

    /** @param runDir the run directory the artifact's relative path is under */
    record Artifact(String source, String category, String name, String path, Map<String, String> metadata,
                    Path runDir) {
    }

    static final String MANIFEST = "manifest.json";
    static final String OWN_SOURCE = "module-quality";

    /**
     * The requested runs — {@code run-id} may list several, comma-separated,
     * when a pipeline's tests ran in several JVMs — merged into one index; or
     * the newest run with a manifest that is not just an earlier quality
     * check's own. Empty when there is none.
     */
    static Optional<RunIndex> find(Path root, String runId, ObjectMapper mapper) {
        if (!Files.isDirectory(root)) {
            if (runId != null) {
                throw new QualityException("No run index directory " + root + " for run " + runId);
            }
            return Optional.empty();
        }
        if (runId != null) {
            List<RunIndex> runs = new ArrayList<>();
            for (String id : runId.split(",")) {
                Path manifest = root.resolve(id.strip()).resolve(MANIFEST);
                if (!Files.isRegularFile(manifest)) {
                    throw new QualityException("Run " + id.strip() + " has no " + MANIFEST + " under " + root);
                }
                runs.add(read(manifest, mapper));
            }
            return Optional.of(merge(runs));
        }
        try (Stream<Path> runs = Files.list(root)) {
            // run ids sort by time (module-reporting's ArtifactRunLayout); newest first
            for (Path run : runs.filter(r -> Files.isRegularFile(r.resolve(MANIFEST)))
                    .sorted(Comparator.comparing((Path r) -> r.getFileName().toString()).reversed()).toList()) {
                RunIndex index = read(run.resolve(MANIFEST), mapper);
                // the gate's own JVM leaves a run holding only its report: never the run to judge
                boolean ownOnly = !index.artifacts().isEmpty()
                        && index.artifacts().stream().allMatch(a -> a.source().equals(OWN_SOURCE));
                if (!ownOnly) {
                    return Optional.of(index);
                }
            }
            return Optional.empty();
        } catch (IOException e) {
            throw new QualityException("Could not list runs under " + root, e);
        }
    }

    private static RunIndex merge(List<RunIndex> runs) {
        if (runs.size() == 1) {
            return runs.getFirst();
        }
        List<Artifact> artifacts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        runs.forEach(run -> {
            artifacts.addAll(run.artifacts());
            problems.addAll(run.reportingProblems());
        });
        return new RunIndex(String.join(",", runs.stream().map(RunIndex::runId).toList()), runs.getFirst().manifest(),
                List.copyOf(artifacts), List.copyOf(problems));
    }

    private static RunIndex read(Path manifest, ObjectMapper mapper) {
        JsonNode root;
        try {
            root = mapper.readTree(manifest.toFile());
        } catch (IOException e) {
            throw new QualityException("Could not read " + manifest, e);
        }
        Path runDir = manifest.getParent();
        List<Artifact> artifacts = new ArrayList<>();
        for (JsonNode node : root.path("artifacts")) {
            Map<String, String> metadata = new LinkedHashMap<>();
            node.path("metadata").properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().asText()));
            artifacts.add(new Artifact(node.path("source").asText(), node.path("category").asText(),
                    node.path("name").asText(), node.path("path").asText(), metadata, runDir));
        }
        List<String> problems = new ArrayList<>();
        root.path("reportingProblems").forEach(problem -> problems.add(problem.asText()));
        return new RunIndex(root.path("runId").asText(runDir.getFileName().toString()), manifest,
                List.copyOf(artifacts), List.copyOf(problems));
    }

    /**
     * Where an artifact's file is: its {@code location} metadata (files kept
     * outside the run directory), else its path inside its run directory.
     */
    Optional<Path> file(Artifact artifact) {
        String location = artifact.metadata().get("location");
        Path file = location != null && !location.isBlank() ? Path.of(location)
                : artifact.path() == null || artifact.path().isBlank() ? null
                : artifact.runDir().resolve(artifact.path());
        return file != null && Files.isRegularFile(file) ? Optional.of(file) : Optional.empty();
    }

    List<Artifact> artifacts(String source, String category) {
        return artifacts.stream().filter(a -> a.source().equals(source) && a.category().equals(category)).toList();
    }
}
