package io.testforge.security;

/** A finding contains descriptions and structural locations, never observed values. */
public record SecurityFinding(
        String id,
        String check,
        Severity severity,
        String target,
        String evidence,
        String owasp,
        String status,
        String reason) {
    public SecurityFinding withStatus(String status, String reason) {
        return new SecurityFinding(id, check, severity, target, evidence, owasp, status, reason);
    }
}
