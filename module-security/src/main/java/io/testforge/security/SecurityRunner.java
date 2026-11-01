package io.testforge.security;

import com.fasterxml.jackson.databind.*;
import io.testforge.artifact.ArtifactSink;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Produces a value-free assessment; only assertAcceptable gates the test run. */
public class SecurityRunner {
  private final SecurityProperties properties;
  private final SecurityCollector collector;
  private final ArtifactSink sink;
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  public SecurityRunner(
      SecurityProperties properties, SecurityCollector collector, ArtifactSink sink) {
    this.properties = properties;
    this.collector = collector;
    this.sink = sink;
  }

  public SecurityReport run() {
    boolean enabled = properties.passive().enabled();
    List<SecurityFinding> findings = new ArrayList<>();
    Set<String> current = new HashSet<>();
    Map<String, String> accepted = new HashMap<>();
    for (String text : properties.acceptedFindings()) {
      int sep = text.indexOf(" # ");
      accepted.put(text.substring(0, sep), text.substring(sep + 3));
    }
    List<SecurityFinding> previous = List.of();
    if (properties.baselineFile() != null && Files.exists(Path.of(properties.baselineFile()))) {
      try {
        SecurityReport baseline =
            mapper.readValue(Path.of(properties.baselineFile()).toFile(), SecurityReport.class);
        if (baseline.formatVersion() != 1)
          throw new IllegalArgumentException("unsupported security baseline format");
        previous =
            baseline.findings().stream().filter(f -> !f.status().equals("resolved")).toList();
      } catch (IOException e) {
        throw new IllegalStateException("security baseline cannot be read");
      }
    }
    Set<String> baselineIds = new HashSet<>();
    previous.forEach(f -> baselineIds.add(f.id()));
    for (SecurityFinding f : collector.findings()) {
      current.add(f.id());
      findings.add(
          accepted.containsKey(f.id())
              ? f.withStatus("accepted", accepted.get(f.id()))
              : baselineIds.contains(f.id())
                  ? f.withStatus("accepted", "present in explicitly promoted baseline")
                  : f);
    }
    List<SecurityCoverage> coverage = new ArrayList<>(collector.coverage());
    boolean complete = coverage.stream().noneMatch(c -> c.status().equals("not_measured"));
    for (SecurityFinding f : previous)
      if (!current.contains(f.id())) {
        boolean checked =
            complete
                && coverage.stream()
                    .anyMatch(
                        c ->
                            c.check().equals(f.check())
                                && (f.target().equals(c.target())
                                    || f.target().startsWith(c.target() + " "))
                                && c.status().equals("measured"));
        if (checked)
          findings.add(
              new SecurityFinding(
                  f.id(),
                  f.check(),
                  f.severity(),
                  f.target(),
                  "previously recorded finding no longer observed",
                  f.owasp(),
                  "resolved",
                  null));
        else
          coverage.add(
              new SecurityCoverage(
                  f.check(),
                  f.target(),
                  null,
                  "not_measured",
                  "baseline finding was not rechecked completely"));
      }
    findings.sort(
        Comparator.comparing(SecurityFinding::severity).thenComparing(SecurityFinding::id));
    boolean measured = coverage.stream().anyMatch(c -> c.status().equals("measured"));
    Map<String, Long> counts = new TreeMap<>();
    for (Severity severity : Severity.values())
      counts.put(
          severity.value(),
          findings.stream()
              .filter(f -> f.status().equals("new") && f.severity() == severity)
              .count());
    boolean healthy =
        enabled
            && measured
            && coverage.stream().noneMatch(c -> c.status().equals("not_measured"))
            && findings.stream()
                .noneMatch(
                    f -> f.status().equals("new") && properties.failOn().contains(f.severity()));
    SecurityReport report =
        new SecurityReport(1, enabled, measured, healthy, findings, coverage, counts);
    if (enabled) write(report);
    return report;
  }

  public SecurityReport assertAcceptable() {
    SecurityReport report = run();
    if (!report.enabled()
        || !report.measured()
        || report.coverage().stream().anyMatch(c -> c.status().equals("not_measured")))
      throw new AssertionError(
          "security assessment is disabled, not measured or incomplete; see coverage");
    if (!report.healthy())
      throw new AssertionError("new security findings meet fail-on severities; see security.md");
    return report;
  }

  public SecurityReport writeBaseline() {
    if (properties.baselineFile() == null)
      throw new IllegalStateException("forge.security.baseline-file is required");
    SecurityReport report = run();
    if (!report.enabled()
        || !report.measured()
        || report.coverage().stream().anyMatch(c -> c.status().equals("not_measured")))
      throw new IllegalStateException(
          "cannot promote an unmeasured or incomplete security baseline");
    try {
      Path p = Path.of(properties.baselineFile());
      if (p.getParent() != null) Files.createDirectories(p.getParent());
      mapper.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), report);
    } catch (IOException e) {
      throw new IllegalStateException("security baseline cannot be written");
    }
    return report;
  }

  private void write(SecurityReport report) {
    Path dir = Path.of(properties.outputDir());
    try {
      Files.createDirectories(dir);
      Path json = dir.resolve("security-findings.json"),
          md = dir.resolve("security.md"),
          sarif = dir.resolve("security.sarif");
      mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
      Files.writeString(md, markdown(report));
      mapper.writerWithDefaultPrettyPrinter().writeValue(sarif.toFile(), sarif(report));
      for (Path p : List.of(json, md, sarif))
        sink.registerFile(
            "module-security",
            "security",
            p.getFileName().toString(),
            p,
            p.equals(md) ? "text/markdown" : "application/json",
            Map.of(
                "healthy",
                Boolean.toString(report.healthy()),
                "measured",
                Boolean.toString(report.measured()),
                "formatVersion",
                "1"));
    } catch (IOException e) {
      throw new IllegalStateException("security reports cannot be written");
    }
  }

  private static String markdown(SecurityReport r) {
    StringBuilder s = new StringBuilder("# Security assessment\n\n");
    s.append("Measured: ")
        .append(r.measured())
        .append("; healthy: ")
        .append(r.healthy())
        .append("\n\n");
    s.append(
        "## Findings\n\n"
            + "| severity | status | check | target | evidence |\n"
            + "|---|---|---|---|---|\n");
    r.findings()
        .forEach(
            f ->
                s.append("| ")
                    .append(f.severity().value())
                    .append(" | ")
                    .append(f.status())
                    .append(" | ")
                    .append(cell(f.check()))
                    .append(" | ")
                    .append(cell(f.target()))
                    .append(" | ")
                    .append(cell(f.evidence()))
                    .append(" |\n"));
    s.append(
        "\n## Coverage\n\n| check | target | role | status | reason |\n|---|---|---|---|---|\n");
    r.coverage()
        .forEach(
            c ->
                s.append("| ")
                    .append(cell(c.check()))
                    .append(" | ")
                    .append(cell(c.target()))
                    .append(" | ")
                    .append(cell(c.role()))
                    .append(" | ")
                    .append(c.status())
                    .append(" | ")
                    .append(cell(c.reason()))
                    .append(" |\n"));
    return s.toString();
  }

  private static String cell(String s) {
    return s == null ? "-" : s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  private static Object sarif(SecurityReport r) {
    var results =
        r.findings().stream()
            .filter(f -> !f.status().equals("resolved"))
            .map(
                f -> {
                  Map<String, Object> result = new LinkedHashMap<>();
                  result.put("ruleId", f.check());
                  result.put(
                      "level",
                      switch (f.severity()) {
                        case CRITICAL, HIGH -> "error";
                        case MEDIUM, LOW -> "warning";
                        case INFO -> "note";
                      });
                  result.put("message", Map.of("text", f.evidence()));
                  result.put(
                      "locations",
                      List.of(Map.of("logicalLocations", List.of(Map.of("name", f.target())))));
                  result.put(
                      "properties", Map.of("id", f.id(), "owasp", f.owasp(), "status", f.status()));
                  if (f.status().equals("accepted"))
                    result.put(
                        "suppressions",
                        List.of(Map.of("kind", "external", "justification", f.reason())));
                  return result;
                })
            .toList();
    return Map.of(
        "version",
        "2.1.0",
        "$schema",
        "https://json.schemastore.org/sarif-2.1.0.json",
        "runs",
        List.of(
            Map.of(
                "tool",
                Map.of("driver", Map.of("name", "TestForge Security")),
                "results",
                results)));
  }
}
