package io.testforge.security;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * testssl.sh's flat JSON as findings and coverage. Each check id becomes {@code testssl-<id>}:
 * measured coverage, plus a finding when testssl.sh rates it LOW or worse, with its own severity.
 * Only the id, severity, CVE and CWE identifiers are kept — testssl.sh's {@code finding} text holds
 * observed values (certificate names and dates, offered ciphers) and never enters the report. A
 * run that did not complete — non-zero exit, FATAL / {@code scanProblem}, an unknown shape — adds
 * no finding and leaves {@code tls-testssl} not_measured, which keeps the gate closed.
 */
final class TestsslAssessment {
    static final String CHECK = "tls-testssl";
    static final int MAX_ENTRIES = 5000;
    private static final Map<String, Severity> RATED =
            Map.of(
                    "CRITICAL", Severity.CRITICAL,
                    "HIGH", Severity.HIGH,
                    "MEDIUM", Severity.MEDIUM,
                    "LOW", Severity.LOW);
    private static final Set<String> UNRATED = Set.of("OK", "INFO", "WARN", "DEBUG");
    // about the local scanner, not the target: the image's OpenSSL engine support, scan duration
    private static final Set<String> META = Set.of("engine_problem", "scanTime");

    private final SecurityCollector collector;

    TestsslAssessment(SecurityCollector collector) {
        this.collector = collector;
    }

    void apply(String target, TestsslEngine.Result result) {
        List<JsonNode> entries = complete(result);
        if (entries == null) {
            collector.covered(
                    CHECK,
                    target,
                    null,
                    "not_measured",
                    "testssl.sh did not complete (phase "
                            + (result == null ? "unknown" : result.phase())
                            + ", exit "
                            + (result == null ? -1 : result.exitCode())
                            + ")");
            return;
        }
        for (JsonNode entry : entries) {
            String id = entry.get("id").asText();
            if (META.contains(id)) continue;
            String check = "testssl-" + id;
            collector.covered(check, target, null, "measured", null);
            Severity severity = RATED.get(entry.get("severity").asText());
            if (severity != null)
                collector.finding(
                        check, severity, target, evidence(id, severity, entry), "API8:2023");
        }
        collector.covered(
                CHECK,
                target,
                null,
                "measured",
                "testssl.sh " + String.join(" ", DockerTestsslEngine.POLICY));
    }

    /** The entries when the run completed and every entry has the known shape, otherwise null. */
    static List<JsonNode> complete(TestsslEngine.Result result) {
        if (result == null
                || result.exitCode() != 0
                || result.report() == null
                || !result.report().isArray()
                || result.report().isEmpty()
                || result.report().size() > MAX_ENTRIES) return null;
        List<JsonNode> entries = new ArrayList<>();
        for (JsonNode entry : result.report()) {
            String id = entry.path("id").asText("");
            String severity = entry.path("severity").asText("");
            if (!id.matches("[A-Za-z0-9_.-]{1,80}")
                    || !(RATED.containsKey(severity) || UNRATED.contains(severity))
                    || id.equals("scanProblem")) return null;
            entries.add(entry);
        }
        return entries;
    }

    /** A fixed description from identifiers only: never testssl.sh's observed finding text. */
    private static String evidence(String id, Severity severity, JsonNode entry) {
        List<String> references = new ArrayList<>();
        for (String field : List.of("cve", "cwe"))
            for (String token : entry.path(field).asText("").split("[\\s,]+"))
                if (token.matches("(CVE-\\d{4}-\\d{4,7}|CWE-\\d{1,5})")) references.add(token);
        return "testssl.sh rated "
                + id
                + " "
                + severity.value()
                + (references.isEmpty() ? "" : " (" + String.join(", ", references) + ")");
    }

    static Path privateDirectory() throws IOException {
        try {
            return Files.createTempDirectory(
                    "testforge-testssl-",
                    PosixFilePermissions.asFileAttribute(
                            PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory("testforge-testssl-");
        }
    }

    static void delete(Path dir) {
        if (dir == null) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // a leftover private temporary directory holds no report the run relies on
        }
    }
}
