package io.testforge.security;

import java.util.*;

/** Keeps only bounded, value-free findings and coverage. Thread-safe across test workers. */
public class SecurityCollector {
  private final int limit;
  private final Map<String, SecurityFinding> findings = new TreeMap<>();
  private final Map<String, SecurityCoverage> coverage = new TreeMap<>();
  private boolean overflow;

  public SecurityCollector(int limit) {
    this.limit = limit;
  }

  public synchronized void finding(
      String check, Severity severity, String target, String description, String owasp) {
    String id = check + " " + target;
    if (findings.containsKey(id)) return;
    if (findings.size() >= limit) {
      overflow = true;
      return;
    }
    findings.put(
        id, new SecurityFinding(id, check, severity, target, description, owasp, "new", null));
  }

  public synchronized void covered(
      String check, String target, String role, String status, String reason) {
    String id = check + " " + target + " " + role + " " + status;
    if (coverage.size() >= limit && !coverage.containsKey(id)) {
      overflow = true;
      return;
    }
    coverage.put(id, new SecurityCoverage(check, target, role, status, reason));
  }

  public synchronized List<SecurityFinding> findings() {
    return List.copyOf(findings.values());
  }

  public synchronized List<SecurityCoverage> coverage() {
    var result = new ArrayList<>(coverage.values());
    if (overflow)
      result.add(
          new SecurityCoverage(
              "collection-limit",
              "run",
              null,
              "not_measured",
              "entry limit reached; coverage is incomplete"));
    return List.copyOf(result);
  }
}
