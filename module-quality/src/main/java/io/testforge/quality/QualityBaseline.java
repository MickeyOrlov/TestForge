package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The last accepted run, as {@code quality-baseline.json}: each dimension's
 * score and issue keys, and every test's duration. Written only by
 * {@link QualityRunner#writeBaseline()} — never by the check itself.
 *
 * @param durations test id to milliseconds, for the efficiency dimension
 */
public record QualityBaseline(String runId, Map<String, Entry> dimensions, Map<String, Long> durations) {

    /** One dimension in the baseline; {@code score} is {@code null} when it was not measured. */
    public record Entry(Integer score, List<String> issues) {

        public Entry {
            issues = List.copyOf(issues == null ? List.of() : issues);
        }
    }

    public QualityBaseline {
        dimensions = Map.copyOf(dimensions == null ? Map.of() : dimensions);
        durations = Map.copyOf(durations == null ? Map.of() : durations);
    }

    static QualityBaseline of(QualityReport report, List<JunitResults.TestCase> tests) {
        Map<String, Entry> dimensions = new LinkedHashMap<>();
        report.dimensions().forEach(d -> dimensions.put(d.name(), new Entry(d.score(), d.issues())));
        Map<String, Long> durations = new LinkedHashMap<>();
        tests.stream().filter(t -> t.millis() >= 0 && t.outcome() == JunitResults.Outcome.PASSED)
                .forEach(t -> durations.put(t.id(), t.millis()));
        return new QualityBaseline(report.runId(), dimensions, durations);
    }

    static Optional<QualityBaseline> read(Path file, ObjectMapper mapper) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(file.toFile(), QualityBaseline.class));
        } catch (IOException e) {
            throw new QualityException("Could not read the quality baseline " + file
                    + " — restore it or write a new one with writeBaseline()", e);
        }
    }

    void write(Path file, ObjectMapper mapper) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new QualityException("Could not write the quality baseline " + file, e);
        }
    }

    /**
     * {@code dimension} against this baseline: an issue is known when the
     * baseline had it, accepted when {@code accepted} lists
     * {@code "<dimension> <issue>"}, new otherwise.
     */
    static QualityDelta compare(QualityDimension dimension, QualityBaseline baseline, List<String> accepted) {
        Entry before = baseline == null ? null : baseline.dimensions().get(dimension.name());
        Set<String> known = before == null ? Set.of() : new LinkedHashSet<>(before.issues());
        List<String> fresh = new ArrayList<>();
        List<String> acceptedNow = new ArrayList<>();
        List<String> knownNow = new ArrayList<>();
        for (String issue : dimension.issues()) {
            if (known.contains(issue)) {
                knownNow.add(issue);
            } else if (accepted.contains(dimension.name() + " " + issue)) {
                acceptedNow.add(issue);
            } else {
                fresh.add(issue);
            }
        }
        List<String> resolved = before == null ? List.of() : before.issues().stream()
                .filter(issue -> !dimension.issues().contains(issue)).toList();
        return new QualityDelta(before == null ? null : before.score(), fresh, acceptedNow, knownNow, resolved);
    }
}
