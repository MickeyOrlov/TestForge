package io.testforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * What static scanners found, from the SARIF 2.1.0 they already write — gitleaks, Trivy, Grype,
 * Semgrep, CodeQL, SpotBugs. The scanners run in the project's build; this only reads their reports.
 *
 * <p>One issue per tool, rule and file, plus the result's fingerprint when the tool gives a non-empty
 * one: no line number, so the baseline holds when code above moves. Severity comes from the rule's
 * {@code security-severity} (CVSS-like, 0–10) when it has one, otherwise from the SARIF level — a
 * result without one is a {@code warning}, as the specification says — and never sits below the
 * configured floor of its tool ({@code gitleaks: high}: gitleaks writes no level, and a leaked secret
 * is no warning). Suppressed results are counted as accepted, a result whose {@code baselineState} is
 * {@code absent} is gone. Messages and snippets are never copied: they may hold the secret.
 */
final class SarifDimension {

    enum Severity { LOW, MEDIUM, HIGH, CRITICAL }

    private SarifDimension() {
    }

    static QualityDimension of(QualityProperties.SarifProperties properties, ObjectMapper mapper, Instant notBefore) {
        if (properties.reports().isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS,
                    "no SARIF report configured (forge.quality.sarif.reports)");
        }
        List<Path> files = new ArrayList<>();
        for (String location : properties.reports()) {
            Path path = Path.of(location);
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else if (Files.isDirectory(path)) {
                try (Stream<Path> found = Files.walk(path)) {
                    found.filter(f -> f.getFileName().toString().matches(".+\\.sarif(\\.json)?")).sorted()
                            .forEach(files::add);
                } catch (IOException e) {
                    throw new QualityException("Could not read SARIF reports under " + path, e);
                }
            } else {
                return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS, "no SARIF report at " + path);
            }
        }
        if (files.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS,
                    "no .sarif file under " + properties.reports());
        }
        Map<String, Severity> issues = new LinkedHashMap<>();
        Map<String, String> where = new LinkedHashMap<>();
        List<String> summaries = new ArrayList<>();
        for (Path file : files) {
            String stale = InputFreshness.stale(file, notBefore);
            if (stale != null) {
                return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS, stale);
            }
            JsonNode report;
            try {
                report = mapper.readTree(file.toFile());
            } catch (IOException e) {
                return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS, file + " is not JSON");
            }
            if (!report.path("version").asText().startsWith("2.1") || !report.path("runs").isArray()) {
                return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS,
                        file + " is not a SARIF 2.1 report (no version 2.1.x / runs)");
            }
            for (JsonNode run : report.path("runs")) {
                String tool = run.path("tool").path("driver").path("name").asText("");
                if (tool.isBlank()) {
                    return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS,
                            file + " has a run without tool.driver.name");
                }
                Map<String, JsonNode> rules = new LinkedHashMap<>();
                run.path("tool").path("driver").path("rules")
                        .forEach(rule -> rules.put(rule.path("id").asText(), rule));
                Severity floor = properties.floor(tool);
                int[] counts = new int[Severity.values().length];
                int suppressed = 0;
                int index = 0;
                for (JsonNode result : run.path("results")) {
                    String ruleId = result.path("ruleId").asText(result.path("rule").path("id").asText(""));
                    if (ruleId.isBlank()) {
                        return QualityDimension.notMeasured(QualityDimension.STATIC_ANALYSIS,
                                file + " has a malformed result (" + tool + " results[" + index + "] has no ruleId)");
                    }
                    index++;
                    if ("absent".equals(result.path("baselineState").asText())) {
                        continue;
                    }
                    if (suppressed(result)) {
                        suppressed++;
                        continue;
                    }
                    Severity severity = severity(result, rules.get(ruleId));
                    if (severity == null) {
                        continue;
                    }
                    if (floor != null && floor.compareTo(severity) > 0) {
                        severity = floor;
                    }
                    counts[severity.ordinal()]++;
                    String key = key(tool, ruleId, result);
                    issues.merge(key, severity, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                    where.putIfAbsent(key, location(result));
                }
                String version = run.path("tool").path("driver").path("version").asText("");
                summaries.add(tool + (version.isBlank() ? "" : " " + version) + ": "
                        + (counts[0] + counts[1] + counts[2] + counts[3]) + " results — " + counts[3] + " critical, "
                        + counts[2] + " high, " + counts[1] + " medium, " + counts[0] + " low; " + suppressed
                        + " suppressed (" + file + ")");
            }
        }
        int[] bySeverity = new int[Severity.values().length];
        issues.values().forEach(severity -> bySeverity[severity.ordinal()]++);
        List<String> facts = new ArrayList<>(summaries);
        issues.entrySet().stream()
                .sorted(Map.Entry.<String, Severity>comparingByValue().reversed())
                .limit(QualityAssessor.LISTED)
                .forEach(e -> facts.add(e.getValue().name().toLowerCase(Locale.ROOT) + ": " + e.getKey()
                        + (where.get(e.getKey()) == null ? "" : " (" + where.get(e.getKey()) + ")")));
        if (issues.size() > QualityAssessor.LISTED) {
            facts.add("… and " + (issues.size() - QualityAssessor.LISTED) + " more");
        }
        int critical = bySeverity[Severity.CRITICAL.ordinal()];
        int high = bySeverity[Severity.HIGH.ordinal()];
        int medium = bySeverity[Severity.MEDIUM.ordinal()];
        int low = bySeverity[Severity.LOW.ordinal()];
        QualityStatus status = critical + high > 0 ? QualityStatus.FAIL
                : medium + low > 0 ? QualityStatus.WARN : QualityStatus.PASS;
        return new QualityDimension(QualityDimension.STATIC_ANALYSIS, status,
                Math.max(0, 100 - 40 * critical - 20 * high - 5 * medium - low),
                "100 − 40 × critical − 20 × high − 5 × medium − 1 × low", facts)
                .withIssues(List.copyOf(issues.keySet()));
    }

    /** Suppressed in source or by an external accepting decision; a rejected suppression does not count. */
    private static boolean suppressed(JsonNode result) {
        JsonNode suppressions = result.path("suppressions");
        if (!suppressions.isArray() || suppressions.isEmpty()) {
            return false;
        }
        for (JsonNode suppression : suppressions) {
            String status = suppression.path("status").asText("accepted");
            if (status.equals("rejected") || status.equals("underReview")) {
                return false;
            }
        }
        return true;
    }

    /** {@code security-severity} first, then the result's or the rule's level; {@code null} for level none. */
    static Severity severity(JsonNode result, JsonNode rule) {
        JsonNode score = result.path("properties").path("security-severity");
        if (score.isMissingNode() && rule != null) {
            score = rule.path("properties").path("security-severity");
        }
        if (!score.isMissingNode()) {
            try {
                double value = Double.parseDouble(score.asText());
                return value >= 9 ? Severity.CRITICAL : value >= 7 ? Severity.HIGH
                        : value >= 4 ? Severity.MEDIUM : Severity.LOW;
            } catch (NumberFormatException e) {
                // not a number: fall back to the level
            }
        }
        String level = result.path("level").asText(rule == null ? ""
                : rule.path("defaultConfiguration").path("level").asText(""));
        return switch (level.isBlank() ? "warning" : level) {
            case "error" -> Severity.HIGH;
            case "note" -> Severity.LOW;
            case "none" -> null;
            default -> Severity.MEDIUM;
        };
    }

    /** {@code <tool> <rule> <file>}, with the tool's own non-empty fingerprint hashed when it gives one. */
    static String key(String tool, String ruleId, JsonNode result) {
        String file = file(result);
        StringBuilder fingerprint = new StringBuilder();
        for (String field : List.of("fingerprints", "partialFingerprints")) {
            result.path(field).properties().stream().sorted(Map.Entry.comparingByKey())
                    .filter(e -> !e.getValue().asText("").isBlank())
                    .forEach(e -> fingerprint.append(e.getKey()).append('=').append(e.getValue().asText()).append('\n'));
        }
        return tool + " " + ruleId + " " + (file == null ? "(no location)" : file)
                + (fingerprint.isEmpty() ? "" : " #" + JunitResults.sha256(fingerprint.toString()).substring(0, 12));
    }

    /** The first location's file, relative as the tool wrote it, without a {@code file://} scheme. */
    private static String file(JsonNode result) {
        String uri = result.path("locations").path(0).path("physicalLocation").path("artifactLocation")
                .path("uri").asText("");
        if (uri.isBlank()) {
            return null;
        }
        String path = uri.replaceFirst("^file://", "");
        Path cwd = Path.of("").toAbsolutePath();
        Path absolute = Path.of(path);
        return absolute.isAbsolute() && absolute.startsWith(cwd) ? cwd.relativize(absolute).toString() : path;
    }

    /** {@code file:line} for the facts — never part of the key. */
    private static String location(JsonNode result) {
        String file = file(result);
        int line = result.path("locations").path(0).path("physicalLocation").path("region")
                .path("startLine").asInt(0);
        return file == null ? null : line > 0 ? file + ":" + line : file;
    }
}
