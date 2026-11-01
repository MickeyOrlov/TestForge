package io.testforge.security;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.oas.models.OpenAPI;
import io.testforge.artifact.ArtifactSink;
import io.testforge.security.BusinessPolicyReport.CaseResult;
import io.testforge.security.BusinessPolicyReport.Issue;
import io.testforge.security.BusinessPolicyReport.Status;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local review lifecycle. Only writeReview updates approval metadata; assessment never does. */
public class BusinessPolicyRunner {
    private final BusinessPolicyProperties properties;
    private final BusinessSecurityProperties business;
    private final SecurityProperties reports;
    private final Set<String> roles;
    private final SecurityCollector collector;
    private final ArtifactSink sink;
    private final ObjectMapper mapper = new ObjectMapper();

    public BusinessPolicyRunner(
            BusinessPolicyProperties properties,
            BusinessSecurityProperties business,
            SecurityProperties reports,
            Set<String> configuredRoles,
            SecurityCollector collector,
            ArtifactSink sink) {
        this.properties = properties;
        this.business = business;
        this.reports = reports;
        Set<String> roles = new HashSet<>(configuredRoles);
        roles.add("anonymous");
        this.roles = Set.copyOf(roles);
        this.collector = collector;
        this.sink = sink;
    }

    public boolean required() {
        return properties.required();
    }

    /** No HTTP or token acquisition. Reads the existing review and writes only an assessment. */
    public BusinessPolicyReport assess(
            OpenAPI document, BusinessSecuritySuite suite, Set<String> inputNames) {
        BusinessPolicyReview reviewed = null;
        try {
            Path file = reviewFile(suite);
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file) || Files.size(file) > properties.maxReviewBytes())
                    throw new BusinessSecurityException("review file exceeds its bound");
                try (var input = Files.newInputStream(file)) {
                    byte[] bytes = input.readNBytes(properties.maxReviewBytes() + 1);
                    if (bytes.length > properties.maxReviewBytes())
                        throw new BusinessSecurityException("review file exceeds its bound");
                    reviewed = mapper.readValue(bytes, BusinessPolicyReview.class);
                }
            }
        } catch (Exception e) {
            var report =
                    report(
                            suite,
                            List.of(
                                    new Issue(
                                            null,
                                            null,
                                            Status.INVALID,
                                            "REVIEW_UNREADABLE",
                                            "review metadata cannot be read or exceeds its"
                                                    + " bound")));
            write(report);
            return report;
        }
        var report = inspect(document, suite, reviewed, inputNames);
        write(report);
        return report;
    }

    /**
     * Pure comparison; useful for tests and callers supplying review metadata from another store.
     */
    public BusinessPolicyReport inspect(
            OpenAPI document,
            BusinessSecuritySuite suite,
            BusinessPolicyReview reviewed,
            Set<String> inputNames) {
        List<Issue> issues = new ArrayList<>();
        BusinessPolicyReview current;
        try {
            current = snapshot(document, suite);
        } catch (Exception e) {
            return report(
                    suite,
                    List.of(
                            new Issue(
                                    null,
                                    null,
                                    Status.INVALID,
                                    "SNAPSHOT_UNSUPPORTED",
                                    "contract or policy exceeds supported structural metadata or"
                                            + " bounds")));
        }
        issues.addAll(preflight(document, suite, current, inputNames));
        if (reviewed == null)
            issues.add(
                    new Issue(
                            null,
                            null,
                            Status.REVIEW_REQUIRED,
                            "REVIEW_MISSING",
                            "no explicit review exists for this policy version"));
        else if (reviewed.formatVersion() != 1)
            issues.add(
                    new Issue(
                            null,
                            null,
                            Status.INVALID,
                            "REVIEW_FORMAT",
                            "review format is unsupported"));
        else {
            if (!reviewed.suite().equals(suite.id())
                    || !reviewed.policyVersion().equals(suite.version()))
                issues.add(
                        new Issue(
                                null,
                                null,
                                Status.REVIEW_REQUIRED,
                                "REVIEW_IDENTITY",
                                "review belongs to another suite or policy version"));
            if (!reviewed.inventory().equals(current.inventory()))
                issues.add(
                        new Issue(
                                null,
                                null,
                                Status.REVIEW_REQUIRED,
                                "INVENTORY_CHANGED",
                                "document operation inventory changed; review coverage and"
                                        + " exclusions"));
            if (!reviewed.scope().equals(current.scope()))
                issues.add(
                        new Issue(
                                null,
                                null,
                                Status.REVIEW_REQUIRED,
                                "POLICY_SCOPE_CHANGED",
                                "roles, tenants, check kinds or exclusions changed"));
            if (!reviewed.cases().keySet().equals(current.cases().keySet()))
                issues.add(
                        new Issue(
                                null,
                                null,
                                Status.REVIEW_REQUIRED,
                                "CASE_SET_CHANGED",
                                "policy case inventory changed"));
            for (var test : suite.cases()) {
                if (!current.cases().get(test.id()).equals(reviewed.cases().get(test.id())))
                    issues.add(
                            new Issue(
                                    test.id(),
                                    null,
                                    Status.REVIEW_REQUIRED,
                                    "POLICY_STRUCTURE_CHANGED",
                                    "case steps, bindings or assertion structure changed"));
                for (String operation : current.dependencies().get(test.id()))
                    if (!java.util.Objects.equals(
                            current.operations().get(operation),
                            reviewed.operations().get(operation))) {
                        issues.add(
                                new Issue(
                                        test.id(),
                                        null,
                                        Status.REVIEW_REQUIRED,
                                        "CONTRACT_CHANGED",
                                        "a used operation, schema, security requirement or Link"
                                                + " changed",
                                        operation));
                        break;
                    }
            }
        }
        return report(suite, issues);
    }

    /**
     * Explicit promotion after project review. Refuses incomplete preflight and preserves old files
     * on failure.
     */
    public BusinessPolicyReview writeReview(
            OpenAPI document, BusinessSecuritySuite suite, Set<String> inputNames) {
        BusinessPolicyReview current;
        try {
            current = snapshot(document, suite);
        } catch (RuntimeException e) {
            write(
                    report(
                            suite,
                            List.of(
                                    new Issue(
                                            null,
                                            null,
                                            Status.INVALID,
                                            "SNAPSHOT_UNSUPPORTED",
                                            "contract or policy exceeds supported structural"
                                                    + " metadata or bounds"))));
            throw new BusinessSecurityException(
                    "cannot review unsupported business policy metadata; see policy report");
        }
        var issues = preflight(document, suite, current, inputNames);
        var assessment = report(suite, issues);
        write(assessment);
        if (assessment.status() != Status.VALID)
            throw new BusinessSecurityException(
                    "cannot review an invalid or unresolved business policy; see policy report");
        Path temporary = null;
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(current);
            if (bytes.length > properties.maxReviewBytes())
                throw new BusinessSecurityException("review metadata exceeds max-review-bytes");
            Path file = reviewFile(suite);
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), ".policy-review-", ".tmp");
            Files.write(temporary, bytes);
            try {
                Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return current;
        } catch (Exception e) {
            throw new BusinessSecurityException("business policy review could not be written");
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                }
        }
    }

    /** Required live-run guard. Static validity never earns measured API-security coverage. */
    public BusinessPolicyReport assertCurrent(
            OpenAPI document, BusinessSecuritySuite suite, Set<String> inputNames) {
        collector.observedBlock("business-policy");
        try {
            var report = assess(document, suite, inputNames);
            if (report.status() != Status.VALID)
                throw new BusinessSecurityException(
                        "business policy is " + report.status() + "; see policy report");
            collector.covered(
                    "business-policy",
                    "suite " + suite.id() + " version " + suite.version(),
                    null,
                    "not_applicable",
                    "static policy metadata validated; live checks are measured by business cases");
            return report;
        } catch (RuntimeException e) {
            collector.covered(
                    "business-policy",
                    "suite " + suite.id() + " version " + suite.version(),
                    null,
                    "not_measured",
                    "business policy is stale, invalid or could not be assessed");
            if (e instanceof BusinessSecurityException safe) throw safe;
            throw new BusinessSecurityException("business policy guard could not complete");
        }
    }

    public Path reviewFile(BusinessSecuritySuite suite) {
        return Path.of(properties.reviewDir())
                .resolve(suite.id() + "-" + suite.version() + ".review.json");
    }

    private BusinessPolicyReview snapshot(OpenAPI document, BusinessSecuritySuite suite) {
        if (suite.cases().size() > business.maxCases()
                || suite.exclusions().size() > business.maxCells()
                || suite.roles().size() > business.maxCells()
                || suite.tenants().size() > properties.maxSchemaNodes())
            throw new BusinessSecurityException("policy scope exceeds configured bounds");
        int planned = 0;
        for (var test : suite.cases()) {
            if (test.steps().size() + test.cleanup().size() > business.maxSteps())
                throw new BusinessSecurityException("policy case exceeds max-steps");
            planned += test.steps().size() + test.cleanup().size();
            for (var step : BusinessContractSnapshotter.steps(test)) {
                if (step.captures().size() > business.maxSteps()
                        || step.assertions().size() > properties.maxSchemaNodes()
                        || step.request().pathParameters().size()
                                        + step.request().queryParameters().size()
                                > properties.maxSchemaNodes()
                        || step.request().body() != null
                                && step.request().body().length() > business.maxBodyBytes())
                    throw new BusinessSecurityException(
                            "policy template exceeds configured bounds");
            }
        }
        if (planned > business.maxRequests())
            throw new BusinessSecurityException("policy work exceeds max-requests");
        return new BusinessContractSnapshotter(properties).snapshot(document, suite);
    }

    private List<Issue> preflight(
            OpenAPI document,
            BusinessSecuritySuite suite,
            BusinessPolicyReview current,
            Set<String> names) {
        if (names == null || names.size() > business.maxSteps())
            return List.of(
                    new Issue(
                            null,
                            null,
                            Status.INVALID,
                            "INPUT_BUDGET",
                            "input aliases exceed the case binding budget"));
        for (String name : names)
            try {
                BusinessSecuritySuite.identifier(name);
            } catch (RuntimeException e) {
                return List.of(
                        new Issue(
                                null,
                                null,
                                Status.INVALID,
                                "INPUT_ALIAS",
                                "input names must be non-sensitive policy aliases"));
            }
        return new BusinessPolicyPreflight(business, properties, roles)
                .check(document, suite, current, Set.copyOf(names));
    }

    private BusinessPolicyReport report(BusinessSecuritySuite suite, List<Issue> issues) {
        List<Issue> distinct = issues.stream().distinct().toList();
        Status overall = status(distinct);
        List<CaseResult> cases =
                suite.cases().stream()
                        .map(
                                test ->
                                        new CaseResult(
                                                test.id(),
                                                status(
                                                        distinct.stream()
                                                                .filter(
                                                                        issue ->
                                                                                test.id()
                                                                                                .equals(
                                                                                                        issue
                                                                                                                .caseId())
                                                                                        || issue
                                                                                                                .caseId()
                                                                                                        == null
                                                                                                && Set
                                                                                                        .of(
                                                                                                                "REVIEW_MISSING",
                                                                                                                "REVIEW_IDENTITY",
                                                                                                                "REVIEW_FORMAT",
                                                                                                                "REVIEW_UNREADABLE",
                                                                                                                "SNAPSHOT_UNSUPPORTED",
                                                                                                                "INPUT_BUDGET",
                                                                                                                "INPUT_ALIAS",
                                                                                                                "CASE_BUDGET",
                                                                                                                "REQUEST_BUDGET",
                                                                                                                "CELL_BUDGET",
                                                                                                                "ISSUE_LIMIT")
                                                                                                        .contains(
                                                                                                                issue
                                                                                                                        .code()))
                                                                .toList())))
                        .toList();
        return new BusinessPolicyReport(1, suite.id(), suite.version(), overall, cases, distinct);
    }

    private static Status status(List<Issue> issues) {
        if (issues.stream().anyMatch(issue -> issue.status() == Status.INVALID))
            return Status.INVALID;
        return issues.isEmpty() ? Status.VALID : Status.REVIEW_REQUIRED;
    }

    private void write(BusinessPolicyReport report) {
        try {
            Path dir = Path.of(reports.outputDir());
            Files.createDirectories(dir);
            String name = "business-policy-" + report.suite() + "-" + report.policyVersion();
            Path json = dir.resolve(name + ".json"), md = dir.resolve(name + ".md");
            mapper.writerWithDefaultPrettyPrinter().writeValue(json.toFile(), report);
            StringBuilder text =
                    new StringBuilder(
                            "# Business policy validity\n\n"
                                    + "Static metadata only: no HTTP requests or measured security"
                                    + " coverage.\n\n"
                                    + "Status: "
                                    + report.status()
                                    + "\n\n| Case | Status |\n|---|---|\n");
            report.cases()
                    .forEach(
                            test ->
                                    text.append("| ")
                                            .append(test.id())
                                            .append(" | ")
                                            .append(test.status())
                                            .append(" |\n"));
            text.append("\n| Case | Step | Code | Location | Reason |\n|---|---|---|---|---|\n");
            report.issues()
                    .forEach(
                            issue ->
                                    text.append("| ")
                                            .append(
                                                    issue.caseId() == null
                                                            ? "suite"
                                                            : issue.caseId())
                                            .append(" | ")
                                            .append(issue.stepId() == null ? "" : issue.stepId())
                                            .append(" | ")
                                            .append(issue.code())
                                            .append(" | ")
                                            .append(
                                                    issue.location() == null
                                                            ? ""
                                                            : issue.location())
                                            .append(" | ")
                                            .append(issue.reason())
                                            .append(" |\n"));
            Files.writeString(md, text);
            for (Path file : List.of(json, md))
                sink.registerFile(
                        "module-security",
                        "business-policy",
                        file.getFileName().toString(),
                        file,
                        file.equals(json) ? "application/json" : "text/markdown",
                        Map.of("status", report.status().name(), "measured", "false"));
        } catch (Exception e) {
            throw new BusinessSecurityException(
                    "business policy assessment report could not be written");
        }
    }
}
