package io.testforge.security.audit;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.testforge.security.audit.SecurityAuditProfile.Engine;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** File-contract bridge to existing security-findings.json v1; never a raw scanner parser. */
public final class SecurityAuditImports {
    private static final List<String> SEVERITIES =
            List.of("critical", "high", "medium", "low", "info");
    private final SecurityAuditResultsProperties properties;
    private final ObjectMapper mapper =
            new ObjectMapper(
                            JsonFactory.builder()
                                    .streamReadConstraints(
                                            StreamReadConstraints.builder()
                                                    .maxNestingDepth(32)
                                                    .maxStringLength(65536)
                                                    .maxNumberLength(12)
                                                    .build())
                                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .build())
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public SecurityAuditImports(SecurityAuditResultsProperties properties) {
        this.properties = properties;
    }

    /** Import a module-security report under its own source, not as a ZAP scan. */
    public SecurityAuditEvidence securityReport(
            String asset, String version, byte[] json, Map<String, String> targetAliases) {
        return normalizedReport(asset, Engine.TESTFORGE, version, json, targetAliases);
    }

    /** Adapter boundary: already-normalized v1 report; caller supplies actual engine provenance. */
    public SecurityAuditEvidence normalizedReport(
            String asset,
            Engine engine,
            String version,
            byte[] json,
            Map<String, String> targetAliases) {
        SecurityAuditProfile.alias(asset);
        new SecurityAuditProfile.EnginePolicy(engine, true, version);
        try {
            if (json == null || json.length > properties.maxBytes()) throw invalid();
            if (targetAliases != null && targetAliases.size() > properties.maxEntries())
                throw invalid();
            Map<String, String> aliases =
                    new TreeMap<>(targetAliases == null ? Map.of() : targetAliases);
            if (aliases.size() > properties.maxEntries()
                    || aliases.values().stream().distinct().count() != aliases.size())
                throw invalid();
            aliases.forEach(
                    (raw, alias) -> {
                        if (raw == null || raw.length() > 4096) throw invalid();
                        SecurityAuditProfile.alias(alias);
                    });
            return new SecurityAuditEvidence(
                    asset, engine, version, project(mapper.readTree(json), aliases), null);
        } catch (Exception e) {
            return new SecurityAuditEvidence(
                    asset,
                    engine,
                    version,
                    null,
                    "upstream normalized security report is invalid, unmapped or exceeds bounds");
        }
    }

    private JsonNode project(JsonNode source, Map<String, String> aliases) {
        fields(
                source,
                Set.of(
                        "formatVersion",
                        "enabled",
                        "measured",
                        "complete",
                        "healthy",
                        "findings",
                        "coverage",
                        "newCounts"));
        if (!source.path("formatVersion").isInt() || source.path("formatVersion").intValue() != 1)
            throw invalid();
        for (String flag : List.of("enabled", "measured", "complete", "healthy"))
            if (!source.path(flag).isBoolean()) throw invalid();
        for (String list : List.of("findings", "coverage"))
            if (!source.path(list).isArray() || source.path(list).size() > properties.maxEntries())
                throw invalid();
        if (source.path("findings").size() + source.path("coverage").size()
                > properties.maxEntries()) throw invalid();
        ObjectNode result = mapper.createObjectNode().put("formatVersion", 1);
        for (String flag : List.of("enabled", "measured", "complete", "healthy"))
            result.put(flag, source.path(flag).booleanValue());
        ObjectNode counts = result.putObject("newCounts");
        SEVERITIES.forEach(s -> counts.put(s, 0));
        var findings = result.putArray("findings");
        Set<String> ids = new HashSet<>();
        for (var finding : source.path("findings")) {
            fields(
                    finding,
                    Set.of(
                            "id",
                            "check",
                            "severity",
                            "target",
                            "evidence",
                            "owasp",
                            "status",
                            "reason"));
            String check = check(finding), target = target(finding, aliases);
            String originalTarget = text(finding, "target", 4096);
            if (!text(finding, "id", 8192).equals(check + " " + originalTarget)) throw invalid();
            String id = check + " " + target;
            if (!ids.add(id)) throw invalid();
            String severity = text(finding, "severity", 16), status = text(finding, "status", 16);
            if (!SEVERITIES.contains(severity)
                    || !Set.of("new", "accepted", "resolved").contains(status)) throw invalid();
            text(finding, "evidence", 65536); // Intentionally discard untrusted free text.
            String owasp = finding.path("owasp").isNull() ? null : text(finding, "owasp", 32);
            if (owasp != null
                    && !owasp.matches("(API[0-9]{1,2}:[0-9]{4}|A[0-9]{2}:[0-9]{4}|CWE-[0-9]{1,6})"))
                throw invalid();
            if (status.equals("accepted") && text(finding, "reason", 65536).isBlank())
                throw invalid();
            else if (!status.equals("accepted") && !finding.path("reason").isNull())
                throw invalid();
            var f =
                    findings.addObject()
                            .put("id", id)
                            .put("check", check)
                            .put("severity", severity)
                            .put("target", target)
                            .put(
                                    "evidence",
                                    status.equals("resolved")
                                            ? "upstream finding is resolved"
                                            : "finding reported by upstream security assessment")
                            .put("status", status);
            f.put("owasp", owasp);
            f.put(
                    "reason",
                    status.equals("accepted") ? "accepted by upstream security policy" : null);
            if (status.equals("new")) counts.put(severity, counts.path(severity).longValue() + 1);
            if (source.path("healthy").booleanValue()
                    && check.equals("artifact-secret")
                    && !status.equals("resolved")) throw invalid();
        }
        fields(source.path("newCounts"), Set.copyOf(SEVERITIES));
        for (String severity : SEVERITIES)
            if (!source.path("newCounts").path(severity).isIntegralNumber()
                    || source.path("newCounts").path(severity).longValue()
                            != counts.path(severity).longValue()) throw invalid();
        var coverage = result.putArray("coverage");
        boolean measured = false, incomplete = false;
        for (var row : source.path("coverage")) {
            fields(row, Set.of("check", "target", "role", "status", "reason"));
            String status = text(row, "status", 32);
            if (!Set.of("measured", "not_measured", "not_applicable").contains(status))
                throw invalid();
            String role = row.path("role").isNull() ? null : text(row, "role", 64);
            // Roles need a future explicit scope mapping; do not copy source role values.
            if (!row.path("reason").isNull()) text(row, "reason", 65536);
            coverage.addObject()
                    .put("check", check(row))
                    .put("target", target(row, aliases))
                    .putNull("role")
                    .put("status", status)
                    .put(
                            "reason",
                            status.equals("measured")
                                    ? null
                                    : "upstream coverage is incomplete or not applicable");
            measured |= status.equals("measured");
            incomplete |= status.equals("not_measured");
        }
        boolean complete = source.path("enabled").booleanValue() && measured && !incomplete;
        if (source.path("measured").booleanValue() != measured
                || source.path("complete").booleanValue() != complete
                || source.path("healthy").booleanValue() && !complete) throw invalid();
        return result;
    }

    private static String check(JsonNode row) {
        String check = text(row, "check", 96);
        if (!check.matches("[a-z][a-z0-9-]{0,95}")) throw invalid();
        return check;
    }

    private static String target(JsonNode row, Map<String, String> aliases) {
        String alias = aliases.get(text(row, "target", 4096));
        if (alias == null) throw invalid();
        return alias;
    }

    private static void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!expected.contains(name)) throw invalid();
                        });
    }

    private static String text(JsonNode node, String field, int max) {
        if (!node.path(field).isTextual() || node.path(field).textValue().length() > max)
            throw invalid();
        return node.path(field).textValue();
    }

    private static IllegalArgumentException invalid() {
        return SecurityAuditProfile.invalid("invalid normalized security evidence");
    }
}
