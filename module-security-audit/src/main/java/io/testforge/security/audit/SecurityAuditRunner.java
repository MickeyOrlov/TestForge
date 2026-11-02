package io.testforge.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.testforge.artifact.ArtifactSink;
import io.testforge.security.audit.SecurityAuditProfile.Engine;
import io.testforge.security.audit.SecurityAuditReport.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Aggregates normalized upstream verdicts; never scans, accepts findings or promotes baselines. */
public final class SecurityAuditRunner {
    private final SecurityAuditProperties properties;
    private final SecurityAuditResultsProperties results;
    private final ArtifactSink sink;
    private final ObjectMapper mapper = new ObjectMapper();

    public SecurityAuditRunner(
            SecurityAuditProperties properties,
            SecurityAuditResultsProperties results,
            ArtifactSink sink) {
        this.properties = properties;
        this.results = results;
        this.sink = sink;
    }

    public SecurityAuditReport assess(
            SecurityAuditPlan plan, List<SecurityAuditEvidence> evidence) {
        validate(plan);
        if (evidence == null || evidence.size() > properties.maxJobs())
            throw SecurityAuditProfile.invalid("audit evidence exceeds job bound");
        Map<String, List<SecurityAuditEvidence>> byJob = new HashMap<>();
        Set<String> expected = new HashSet<>();
        for (var job : plan.jobs())
            if (job.status() == SecurityAuditPlan.Status.PLANNED)
                expected.add(key(job.asset(), job.engine()));
        long bytes = 0;
        int entries = 0;
        for (var source : evidence) {
            if (source == null || !expected.contains(key(source.asset(), source.engine())))
                throw SecurityAuditProfile.invalid("audit evidence does not match a planned job");
            JsonNode report = source.securityReport();
            if (report != null) {
                bytes += report.toString().getBytes(StandardCharsets.UTF_8).length;
                entries += report.path("findings").size() + report.path("coverage").size();
                if (bytes > results.maxBytes() || entries > results.maxEntries())
                    throw SecurityAuditProfile.invalid(
                            "aggregate audit evidence exceeds report bounds");
            }
            byJob.computeIfAbsent(
                            key(source.asset(), source.engine()), ignored -> new ArrayList<>())
                    .add(source);
        }
        var jobs = new ArrayList<SecurityAuditReport.Job>();
        for (var job :
                plan.jobs().stream()
                        .sorted(
                                Comparator.comparing(SecurityAuditPlan.Job::asset)
                                        .thenComparing(SecurityAuditPlan.Job::engine))
                        .toList()) {
            Status status;
            String reason, sourceVersion = null;
            JsonNode report = null;
            switch (job.status()) {
                case DISABLED -> {
                    status = Status.DISABLED;
                    reason = "engine was not explicitly enabled";
                }
                case NOT_APPLICABLE -> {
                    status = Status.NOT_APPLICABLE;
                    reason = "engine does not support this layer and target kind";
                }
                case BLOCKED -> {
                    status = Status.BLOCKED;
                    reason = "planned job prerequisites were not established";
                }
                case PLANNED -> {
                    var sources = byJob.getOrDefault(key(job.asset(), job.engine()), List.of());
                    status = Status.INCOMPLETE;
                    if (sources.size() != 1)
                        reason =
                                sources.isEmpty()
                                        ? "planned job has no upstream evidence"
                                        : "planned job has ambiguous duplicate evidence";
                    else {
                        var source = sources.getFirst();
                        sourceVersion = source.version();
                        report = source.securityReport();
                        if (!source.version().equals(job.version()))
                            reason = "upstream engine version differs from the plan";
                        else if (source.failure() != null || report == null)
                            reason = "upstream normalized report could not be imported";
                        else if (!report.path("enabled").booleanValue()
                                || !report.path("measured").booleanValue()
                                || !report.path("complete").booleanValue())
                            reason =
                                    "upstream security assessment is disabled, unmeasured or"
                                            + " incomplete";
                        else {
                            status = Status.MEASURED;
                            reason = "normalized upstream assessment completed";
                        }
                    }
                }
                default -> throw SecurityAuditProfile.invalid("invalid audit plan state");
            }
            jobs.add(
                    new SecurityAuditReport.Job(
                            job.asset(),
                            job.layer(),
                            job.kind(),
                            job.engine(),
                            job.version(),
                            sourceVersion,
                            status,
                            reason,
                            report == null ? null : "testforge-security-v1",
                            report));
        }
        boolean measured =
                jobs.stream()
                        .anyMatch(
                                j ->
                                        j.security() != null
                                                && j.security().path("measured").booleanValue());
        boolean complete =
                measured
                        && jobs.stream()
                                .noneMatch(
                                        j ->
                                                Set.of(Status.INCOMPLETE, Status.BLOCKED)
                                                        .contains(j.status()));
        boolean healthy =
                complete
                        && jobs.stream()
                                .filter(j -> j.status() == Status.MEASURED)
                                .allMatch(j -> j.security().path("healthy").booleanValue());
        return new SecurityAuditReport(
                1,
                "IMPORTED",
                plan.profile(),
                plan.environment(),
                true,
                measured,
                complete,
                healthy,
                jobs);
    }

    /** Writes only this invocation's report, in a fresh private directory, and registers it. */
    public SecurityAuditReport run(SecurityAuditPlan plan, List<SecurityAuditEvidence> evidence) {
        var report = assess(plan, evidence);
        try {
            byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(report);
            byte[] markdown = markdown(report).getBytes(StandardCharsets.UTF_8);
            if (json.length > results.maxBytes() || markdown.length > results.maxBytes())
                throw SecurityAuditProfile.invalid("rendered audit report exceeds byte bound");
            Path root = Path.of(results.outputDir()).toAbsolutePath().normalize();
            for (Path part = root; part != null; part = part.getParent())
                if (Files.isSymbolicLink(part)) throw new IOException();
            Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            Path directory;
            try {
                directory =
                        Files.createTempDirectory(
                                root,
                                "audit-",
                                PosixFilePermissions.asFileAttribute(
                                        PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                directory = Files.createTempDirectory(root, "audit-");
            }
            Path jsonFile = directory.resolve("security-audit.json"),
                    mdFile = directory.resolve("security-audit.md");
            Files.write(jsonFile, json, java.nio.file.StandardOpenOption.CREATE_NEW);
            Files.write(mdFile, markdown, java.nio.file.StandardOpenOption.CREATE_NEW);
            Map<String, String> metadata =
                    Map.of(
                            "formatVersion",
                            "1",
                            "measured",
                            Boolean.toString(report.measured()),
                            "complete",
                            Boolean.toString(report.complete()),
                            "healthy",
                            Boolean.toString(report.healthy()));
            for (Path file : List.of(jsonFile, mdFile)) {
                try {
                    sink.registerFile(
                            "module-security-audit",
                            "security-audit",
                            file.getFileName().toString(),
                            file,
                            file.equals(mdFile) ? "text/markdown" : "application/json",
                            metadata);
                } catch (RuntimeException ignored) {
                    /* ArtifactSink is best-effort; report writing is explicit. */
                }
            }
            return report;
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            throw new IllegalStateException(
                    "audit reports could not be written; inspect private output configuration");
        }
    }

    private void validate(SecurityAuditPlan plan) {
        if (!properties.enabled())
            throw SecurityAuditProfile.invalid(
                    "forge.security-audit.enabled must be true for assessment");
        if (plan == null
                || plan.formatVersion() != 1
                || plan.executed()
                || plan.environment() == null
                || plan.budget() == null
                || plan.jobs().isEmpty()
                || plan.jobs().size() > properties.maxJobs())
            throw SecurityAuditProfile.invalid(
                    "audit assessment requires a valid declaration plan");
        SecurityAuditProfile.alias(plan.profile());
        var budget = plan.budget();
        if (budget.maxRequests() > properties.maxRequests()
                || budget.timeoutSeconds() > properties.timeoutSeconds()
                || budget.maxConcurrent() > properties.maxConcurrent())
            throw SecurityAuditProfile.invalid("audit plan exceeds administrative ceilings");
        var assets = new HashMap<String, SecurityAuditPlan.Job>();
        Set<String> cells = new HashSet<>();
        for (var job : plan.jobs()) {
            SecurityAuditProfile.alias(job.asset());
            if (job.engine() == null
                    || job.layer() == null
                    || job.kind() == null
                    || job.status() == null
                    || !cells.add(key(job.asset(), job.engine())))
                throw SecurityAuditProfile.invalid("audit plan cells must be unique and explicit");
            var first = assets.putIfAbsent(job.asset(), job);
            if (first != null && (first.layer() != job.layer() || first.kind() != job.kind()))
                throw SecurityAuditProfile.invalid("audit asset metadata is inconsistent");
            boolean supported =
                    SecurityAuditPlanner.supports(job.engine(), job.layer(), job.kind());
            if (supported == (job.status() == SecurityAuditPlan.Status.NOT_APPLICABLE))
                throw SecurityAuditProfile.invalid("audit plan has incompatible cell status");
            new SecurityAuditProfile.EnginePolicy(
                    job.engine(),
                    Set.of(SecurityAuditPlan.Status.PLANNED, SecurityAuditPlan.Status.BLOCKED)
                            .contains(job.status()),
                    job.version());
        }
        if (assets.size() > properties.maxAssets()
                || cells.size() != assets.size() * Engine.values().length)
            throw SecurityAuditProfile.invalid("audit plan matrix is incomplete or exceeds bounds");
    }

    private static String key(String asset, Engine engine) {
        return asset + " " + engine;
    }

    private static String markdown(SecurityAuditReport report) {
        var s =
                new StringBuilder("# Security audit\n\nProfile: ")
                        .append(report.profile())
                        .append("; environment: ")
                        .append(report.environment())
                        .append("\n\nMeasured: ")
                        .append(report.measured())
                        .append("; complete: ")
                        .append(report.complete())
                        .append("; healthy: ")
                        .append(report.healthy())
                        .append(
                                "\n\n"
                                    + "Imported upstream assessments only; no scanner was launched"
                                    + " by this report stage.\n\n")
                        .append(
                                "| Asset | Layer | Engine | Planned version | Evidence version |"
                                        + " Status | Reason |\n"
                                        + "|---|---|---|---|---|---|---|\n");
        for (var job : report.jobs())
            s.append("| ")
                    .append(job.asset())
                    .append(" | ")
                    .append(job.layer())
                    .append(" | ")
                    .append(job.engine())
                    .append(" | ")
                    .append(job.version() == null ? "-" : job.version())
                    .append(" | ")
                    .append(job.sourceVersion() == null ? "-" : job.sourceVersion())
                    .append(" | ")
                    .append(job.status())
                    .append(" | ")
                    .append(job.reason())
                    .append(" |\n");
        s.append(
                        "\n"
                            + "## Findings\n\n"
                            + "Targets are declared aliases. Free-text source evidence/reasons are"
                            + " withheld; consult the restricted upstream report.\n\n")
                .append(
                        "| Asset | Engine | Check | Target alias | Severity | Upstream status |\n"
                                + "|---|---|---|---|---|---|\n");
        for (var job : report.jobs()) {
            JsonNode security = job.security();
            if (security != null)
                for (var f : security.path("findings"))
                    s.append("| ")
                            .append(job.asset())
                            .append(" | ")
                            .append(job.engine())
                            .append(" | ")
                            .append(f.path("check").asText())
                            .append(" | ")
                            .append(f.path("target").asText())
                            .append(" | ")
                            .append(f.path("severity").asText())
                            .append(" | ")
                            .append(f.path("status").asText())
                            .append(" |\n");
        }
        return s.toString();
    }
}
