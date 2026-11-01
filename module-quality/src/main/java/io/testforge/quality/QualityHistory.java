package io.testforge.quality;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The last runs, one small file each under {@code forge.quality.history.dir}:
 * every dimension's score, every test's outcome and duration and every API
 * operation's p95 — no message, no payload. The directory travels between CI runs as an artifact
 * or a cache; there is no database.
 *
 * <p>Advisory: an entry that cannot be read is listed and skipped, never a
 * failed assessment — the history sharpens stability and efficiency, the
 * run's own results still decide.
 *
 * @param entries the earlier runs read, oldest first, this run excluded
 * @param skipped files that were not used, with the reason
 */
public record QualityHistory(List<Entry> entries, List<String> skipped) {

    static final int FORMAT_VERSION = 1;
    static final String PREFIX = "run-";

    /**
     * One run as the history keeps it.
     *
     * @param recordedAt ISO instant the entry was written
     * @param revision   the code the run tested (commit), {@code null} when unknown
     * @param outcomes   test id to {@code PASSED}, {@code FAILED} or {@code FLAKY}
     * @param durations  test id to milliseconds, passed tests only
     * @param latencies  API operation (module-http's shape) to its p95 in milliseconds
     * @param labels     the run's metadata, as in the report
     */
    public record Entry(int formatVersion, String runId, String recordedAt, String revision, String overall,
                        Map<String, Integer> scores, Map<String, String> outcomes, Map<String, Long> durations,
                        Map<String, Long> latencies, Map<String, String> labels) {

        public Entry {
            scores = scores == null ? Map.of() : new LinkedHashMap<>(scores);
            outcomes = outcomes == null ? Map.of() : Map.copyOf(outcomes);
            durations = durations == null ? Map.of() : Map.copyOf(durations);
            latencies = latencies == null ? Map.of() : Map.copyOf(latencies);
            labels = labels == null ? Map.of() : Map.copyOf(labels);
        }

        public Entry(int formatVersion, String runId, String recordedAt, String revision, String overall,
                     Map<String, Integer> scores, Map<String, String> outcomes, Map<String, Long> durations,
                     Map<String, Long> latencies) {
            this(formatVersion, runId, recordedAt, revision, overall, scores, outcomes, durations, latencies, null);
        }

        public Entry(int formatVersion, String runId, String recordedAt, String revision, String overall,
                     Map<String, Integer> scores, Map<String, String> outcomes, Map<String, Long> durations) {
            this(formatVersion, runId, recordedAt, revision, overall, scores, outcomes, durations, null, null);
        }
    }

    public QualityHistory {
        entries = List.copyOf(entries);
        skipped = List.copyOf(skipped);
    }

    static QualityHistory empty() {
        return new QualityHistory(List.of(), List.of());
    }

    /** The last {@code window} entries in {@code dir}, without the entry of {@code runId} itself. */
    static QualityHistory read(Path dir, String runId, int window, ObjectMapper mapper) {
        List<String> skipped = new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        for (Located located : all(dir, mapper, skipped)) {
            if (runId == null || !runId.equals(located.entry().runId())) {
                entries.add(located.entry());
            }
        }
        return new QualityHistory(entries.subList(Math.max(0, entries.size() - window), entries.size()), skipped);
    }

    static Entry of(QualityReport report, List<JunitResults.TestCase> tests, String revision, Instant now,
                    Map<String, Long> latencies) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        report.dimensions().stream().filter(d -> d.score() != null).forEach(d -> scores.put(d.name(), d.score()));
        Map<String, String> outcomes = new LinkedHashMap<>();
        Map<String, Long> durations = new LinkedHashMap<>();
        for (JunitResults.TestCase test : tests) {
            if (test.outcome() == JunitResults.Outcome.SKIPPED) {
                continue;
            }
            outcomes.put(test.id(), test.flaky() ? "FLAKY" : test.outcome().name());
            if (test.outcome() == JunitResults.Outcome.PASSED && test.millis() >= 0) {
                durations.put(test.id(), test.millis());
            }
        }
        return new Entry(FORMAT_VERSION, report.runId(), now.toString(), revision, report.overall().name(), scores,
                outcomes, durations, latencies, report.labels());
    }

    /**
     * Adds {@code entry} — replacing an earlier entry of the same run, so a gate run twice counts
     * once — then drops the oldest entries beyond {@code keep}. Never edits another entry.
     */
    static void record(Path dir, Entry entry, int keep, ObjectMapper mapper) {
        try {
            Files.createDirectories(dir);
            String name = entry.runId() == null ? entry.recordedAt() : entry.runId();
            Path file = dir.resolve(PREFIX + name.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
            mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), entry);
            List<Located> all = all(dir, mapper, new ArrayList<>());
            for (int i = 0; i < all.size() - keep; i++) {
                Files.deleteIfExists(all.get(i).file());
            }
        } catch (IOException e) {
            throw new QualityException("Could not record the run in the quality history " + dir, e);
        }
    }

    /** Earlier entries that tested the same code; none when {@code revision} is unknown. */
    List<Entry> sameRevision(String revision) {
        return revision == null ? List.of() : entries.stream().filter(e -> revision.equals(e.revision())).toList();
    }

    private record Located(Path file, Entry entry, Instant recordedAt) {
    }

    private static List<Located> all(Path dir, ObjectMapper mapper, List<String> skipped) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Located> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.getFileName().toString().startsWith(PREFIX)
                    && f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    Entry entry = mapper.readerFor(Entry.class)
                            .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(file.toFile());
                    if (entry.formatVersion() != FORMAT_VERSION) {
                        skipped.add(file.getFileName() + " (formatVersion " + entry.formatVersion() + ")");
                    } else {
                        found.add(new Located(file, entry, Instant.parse(entry.recordedAt())));
                    }
                } catch (IOException | RuntimeException e) {
                    skipped.add(file.getFileName() + " (unreadable)");
                }
            }
        } catch (IOException e) {
            throw new QualityException("Could not list the quality history " + dir, e);
        }
        // by instant, not by text: "12:00:00.5Z" sorts before "12:00:00Z" as a string
        found.sort(Comparator.comparing(Located::recordedAt));
        return found;
    }
}
