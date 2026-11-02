package io.testforge.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.quality.JunitResults.Outcome;
import io.testforge.quality.JunitResults.TestCase;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Dimensions read from other modules' reports in the run index. Each module
 * is optional: without its report the dimension is NOT MEASURED and says
 * which module would measure it. Reports are read as JSON — no module class
 * is touched, so deleting a module never breaks this one.
 *
 * <p>A run-wide summary comes once per Spring context of the test JVM ({@code http-summary.json},
 * {@code http-summary-2.json}, …, all registered under the one name): each dimension merges them —
 * per operation, statement shape, flow — so an issue counts once however many contexts saw it.
 */
final class ModuleDimensions {

    static final int LISTED = 10;
    static final String SECURITY_INCOMPLETE = "security measurement incomplete";
    /** The report layouts this module reads; a report without {@code formatVersion} is version 1. */
    static final int FORMAT_VERSION = 1;

    private ModuleDimensions() {
    }

    /** API responses against their OpenAPI schema (contract guard) and event payloads (contract monitor). */
    static QualityDimension contracts(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> guards = reports(index, "module-api-explorer", "contract-guard", "contract-guard.json", mapper);
        List<JsonNode> monitors = reports(index, "module-contract-monitor", "contract-monitor", "report.json", mapper)
                .stream().filter(report -> report.has("cases")).toList();
        if (guards.isEmpty() && monitors.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.CONTRACTS, "no contract-guard report "
                    + "(module-api-explorer) and no contract-monitor report (module-contract-monitor) in the run");
        }
        List<String> issues = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        long checked = 0;
        for (JsonNode guard : guards) {
            checked += guard.path("checkedResponses").asLong();
            for (JsonNode finding : guard.path("findings")) {
                issues.add("api " + finding.path("operation").asText() + " " + finding.path("kind").asText() + " "
                        + finding.path("location").asText());
            }
        }
        int cases = 0;
        for (JsonNode monitor : monitors) {
            for (JsonNode each : monitor.path("cases")) {
                cases++;
                if (each.path("failed").asBoolean()) {
                    issues.add("event " + each.path("name").asText());
                }
            }
        }
        if (!guards.isEmpty()) {
            facts.add(checked + " API responses checked against their schema");
        }
        if (!monitors.isEmpty()) {
            facts.add(cases + " event contract cases checked");
        }
        issues = new ArrayList<>(new java.util.LinkedHashSet<>(issues));
        facts.add(issues.size() + " contract mismatches");
        list(facts, "mismatch", issues);
        return new QualityDimension(QualityDimension.CONTRACTS, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.FAIL, Math.max(0, 100 - 10 * issues.size()), "100 − 10 × mismatches", facts)
                .withIssues(issues);
    }

    /** Schema changes against the database contract's baseline (module-db-contract). */
    static QualityDimension data(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-db-contract", "db-contract", "report.json", mapper).stream()
                .filter(report -> report.has("changes")).toList();
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.DATA,
                    "no db-contract report (module-db-contract) in the run");
        }
        int breaking = 0;
        int risky = 0;
        int unknown = 0;
        List<String> issues = new ArrayList<>();
        for (JsonNode report : reports) {
            for (JsonNode assessment : report.path("changes")) {
                String compatibility = assessment.path("compatibility").asText();
                JsonNode change = assessment.path("change");
                String key = compatibility + " " + change.path("type").asText() + " " + change.path("table").asText()
                        + (change.path("object").isTextual() ? "." + change.path("object").asText() : "");
                switch (compatibility) {
                    case "BREAKING" -> {
                        breaking++;
                        issues.add(key);
                    }
                    case "RISKY" -> {
                        risky++;
                        issues.add(key);
                    }
                    case "UNKNOWN" -> {
                        unknown++;
                        issues.add(key);
                    }
                    default -> {
                    }
                }
            }
        }
        List<String> facts = new ArrayList<>();
        facts.add(breaking + " breaking, " + risky + " risky, " + unknown + " unclassified schema changes");
        list(facts, "change", issues);
        QualityStatus status = breaking > 0 ? QualityStatus.FAIL
                : risky + unknown > 0 ? QualityStatus.WARN : QualityStatus.PASS;
        return new QualityDimension(QualityDimension.DATA, status,
                Math.max(0, 100 - 50 * breaking - 20 * risky - 5 * unknown),
                "100 − 50 × breaking − 20 × risky − 5 × unknown", facts).withIssues(issues);
    }

    /** Fuzz finding groups nobody accepted (module-api-fuzz). */
    static QualityDimension robustness(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-api-fuzz", "api-fuzz", "findings.json", mapper).stream()
                .filter(report -> report.has("groups")).toList();
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.ROBUSTNESS,
                    "no api-fuzz findings.json (module-api-fuzz) in the run");
        }
        List<String> issues = new ArrayList<>();
        int accepted = 0;
        List<String> facts = new ArrayList<>();
        for (JsonNode report : reports) {
            String outcome = report.path("outcome").asText();
            if (outcome.equals("EXECUTION_ERROR") || outcome.equals("CONFIGURATION_ERROR")) {
                return QualityDimension.notMeasured(QualityDimension.ROBUSTNESS,
                        "the fuzz run did not complete (" + outcome + "): nothing it found or missed can be judged");
            }
            for (JsonNode group : report.path("groups")) {
                if (group.path("accepted").asBoolean()) {
                    accepted++;
                } else {
                    issues.add(group.path("specId").asText() + ":" + group.path("key").asText());
                }
            }
        }
        facts.add(issues.size() + " finding groups not accepted, " + accepted + " accepted in the fuzz run");
        list(facts, "finding", issues);
        return new QualityDimension(QualityDimension.ROBUSTNESS, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.FAIL, Math.max(0, 100 - 10 * issues.size()), "100 − 10 × finding groups", facts)
                .withIssues(issues);
    }

    /** Roles that got in where they should not, or were shut out (module-api-explorer's access matrix). */
    static QualityDimension authorization(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-api-explorer", "access-matrix", "access-matrix.json", mapper).stream()
                .filter(report -> report.has("rows")).toList();
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.AUTHORIZATION,
                    "no access-matrix report (module-api-explorer) in the run");
        }
        List<String> issues = new ArrayList<>();
        int cells = 0;
        for (JsonNode report : reports) {
            for (JsonNode row : report.path("rows")) {
                for (JsonNode cell : row.path("cells")) {
                    cells++;
                    if (cell.path("verdict").asText().equals("VIOLATION")) {
                        issues.add(row.path("key").asText() + " as " + cell.path("role").asText());
                    }
                }
            }
        }
        List<String> facts = new ArrayList<>();
        facts.add(cells + " operation × role checks, " + issues.size() + " violations");
        list(facts, "violation", issues);
        return new QualityDimension(QualityDimension.AUTHORIZATION, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.FAIL, Math.max(0, 100 - 20 * issues.size()), "100 − 20 × violations", facts)
                .withIssues(issues);
    }

    /**
     * New findings of {@code module-security} ({@code security-findings.json}),
     * weighted by severity. That module keeps its own accepted list and
     * baseline; what it reports as {@code new} is what this dimension scores.
     */
    static QualityDimension security(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-security", "security", "security-findings.json", mapper).stream()
                .filter(report -> report.has("findings")).toList();
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.SECURITY,
                    "no security-findings.json (module-security) in the run");
        }
        boolean complete = reports.stream().allMatch(ModuleDimensions::completeSecurityRun);
        java.util.Map<String, Integer> bySeverity = new java.util.LinkedHashMap<>();
        for (String severity : List.of("critical", "high", "medium", "low", "info")) {
            bySeverity.put(severity, 0);
        }
        List<String> issues = new ArrayList<>();
        List<String> listed = new ArrayList<>();
        int accepted = 0;
        int resolved = 0;
        for (JsonNode report : reports) {
            for (JsonNode finding : report.path("findings")) {
                String status = finding.path("status").asText();
                if (status.equals("accepted")) {
                    accepted++;
                } else if (status.equals("resolved")) {
                    resolved++;
                } else if (status.equals("new")) {
                    String severity = finding.path("severity").asText().toLowerCase(java.util.Locale.ROOT);
                    bySeverity.merge(severity, 1, Integer::sum);
                    if (!severity.equals("info")) {
                        issues.add(finding.path("id").asText());
                        listed.add(severity + ": " + finding.path("id").asText());
                    }
                }
            }
        }
        int critical = bySeverity.getOrDefault("critical", 0);
        int high = bySeverity.getOrDefault("high", 0);
        int medium = bySeverity.getOrDefault("medium", 0);
        int low = bySeverity.getOrDefault("low", 0);
        List<String> facts = new ArrayList<>();
        facts.add("new findings: " + critical + " critical, " + high + " high, " + medium + " medium, " + low
                + " low, " + bySeverity.getOrDefault("info", 0) + " info; " + accepted + " accepted, " + resolved
                + " resolved (module-security)");
        listed.stream().limit(LISTED).forEach(facts::add);
        if (listed.size() > LISTED) {
            facts.add("… and " + (listed.size() - LISTED) + " more");
        }
        if (!complete) {
            facts.add("security measurement incomplete — see coverage in security.md");
            issues.add(SECURITY_INCOMPLETE);
            if (critical + high == 0) {
                return new QualityDimension(QualityDimension.SECURITY, QualityStatus.NOT_MEASURED,
                        null, null, facts).withIssues(issues);
            }
        }
        QualityStatus status = critical + high > 0 ? QualityStatus.FAIL
                : medium + low > 0 ? QualityStatus.WARN : QualityStatus.PASS;
        return new QualityDimension(QualityDimension.SECURITY, status,
                Math.max(0, 100 - 40 * critical - 20 * high - 5 * medium - low),
                "100 − 40 × critical − 20 × high − 5 × medium − 1 × low (new findings)", facts).withIssues(issues);
    }

    /** Each API operation's p95 in this run (module-http's traffic summary); empty without one. */
    static java.util.Map<String, Long> latencies(RunIndex index, ObjectMapper mapper) {
        java.util.Map<String, Long> latencies = new java.util.TreeMap<>();
        for (JsonNode report : reports(index, "module-http", "http-summary", "http-summary.json", mapper)) {
            for (JsonNode operation : report.path("operations")) {
                if (operation.path("requests").asLong() > 0 && operation.has("p95Millis")) {
                    latencies.merge(operation.path("operation").asText(), operation.path("p95Millis").asLong(),
                            Math::max);
                }
            }
        }
        return latencies;
    }

    /**
     * Operations that answered 5xx or failed in transport during the run (module-http's traffic
     * summary) — including those a test expected or swallowed: the suite passing does not mean the
     * service did.
     */
    static QualityDimension reliability(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-http", "http-summary", "http-summary.json", mapper);
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.RELIABILITY,
                    "no http-summary.json (module-http) in the run");
        }
        java.util.Map<String, long[]> operations = new java.util.TreeMap<>();
        for (JsonNode report : reports) {
            for (JsonNode operation : report.path("operations")) {
                long[] counts = operations.computeIfAbsent(operation.path("operation").asText(), k -> new long[2]);
                counts[0] += operation.path("requests").asLong();
                counts[1] += operation.path("status").path("5xx").asLong() + operation.path("transportErrors").asLong();
            }
        }
        long requests = 0;
        long failed = 0;
        List<String> issues = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        for (java.util.Map.Entry<String, long[]> operation : operations.entrySet()) {
            long count = operation.getValue()[0];
            long errors = operation.getValue()[1];
            requests += count;
            failed += errors;
            if (errors > 0) {
                issues.add(operation.getKey());
                if (issues.size() <= LISTED) {
                    facts.add("server error: " + operation.getKey() + " " + errors + " of " + count
                            + " requests answered 5xx or failed in transport");
                }
            }
        }
        if (requests == 0) {
            return QualityDimension.notMeasured(QualityDimension.RELIABILITY, "no HTTP request in the run");
        }
        facts.addFirst(requests + " HTTP requests, " + failed + " answered 5xx or failed in transport, across "
                + issues.size() + " operations");
        return new QualityDimension(QualityDimension.RELIABILITY, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.FAIL, Math.round(100f * (requests - failed) / requests),
                "100 × requests without 5xx / requests", facts).withIssues(issues);
    }

    /**
     * The run's statements by shape (module-db's query summary): one that failed — swallowed by the
     * application or not — or wrote every row of a table fails; one over the slow threshold warns.
     */
    static QualityDimension dbQueries(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-db", "db-queries", "db-queries.json", mapper);
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.DB_QUERIES,
                    "no db-queries.json (module-db with forge.db.queries.enabled=true) in the run");
        }
        long statements = 0;
        // per shape: failed, unbounded, slow — in any context
        java.util.Map<String, boolean[]> shapes = new java.util.LinkedHashMap<>();
        for (JsonNode report : reports) {
            statements += report.path("statements").asLong();
            for (JsonNode shape : report.path("shapes")) {
                boolean[] flags = shapes.computeIfAbsent(shape.path("shape").asText(), k -> new boolean[3]);
                flags[0] |= shape.path("errors").asLong() > 0;
                flags[1] |= shape.path("unboundedWrite").asBoolean();
                flags[2] |= shape.path("slow").asBoolean();
            }
        }
        boolean failing = false;
        List<String> issues = new ArrayList<>();
        for (java.util.Map.Entry<String, boolean[]> shape : shapes.entrySet()) {
            boolean[] flags = shape.getValue();
            if (flags[0]) {
                issues.add("failed " + shape.getKey());
            }
            if (flags[1]) {
                issues.add("unbounded " + shape.getKey());
            }
            if (flags[2]) {
                issues.add("slow " + shape.getKey());
            }
            failing |= flags[0] || flags[1];
        }
        List<String> facts = new ArrayList<>();
        facts.add(statements + " statements in " + shapes.size() + " shapes, " + issues.size() + " flagged");
        list(facts, "statement", issues);
        return new QualityDimension(QualityDimension.DB_QUERIES, failing ? QualityStatus.FAIL
                : issues.isEmpty() ? QualityStatus.PASS : QualityStatus.WARN, Math.max(0, 100 - 10 * issues.size()),
                "100 − 10 × flagged statement shapes", facts).withIssues(issues);
    }

    /**
     * Documented operations the suite never called (module-api-explorer's contract guard): the parts of
     * the API no test looks at. WARN while any is uncalled, FAIL below {@code min-api-coverage}.
     */
    static QualityDimension apiCoverage(RunIndex index, ObjectMapper mapper, Double minimum) {
        List<JsonNode> reports = reports(index, "module-api-explorer", "api-coverage", "api-coverage.json", mapper);
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.API_COVERAGE,
                    "no api-coverage.json (module-api-explorer contract guard) in the run");
        }
        // one context's uncalled operation may be another's called one: a union, not a sum
        java.util.Set<String> operations = new java.util.LinkedHashSet<>();
        java.util.Set<String> calledSomewhere = new java.util.LinkedHashSet<>();
        for (JsonNode report : reports) {
            report.path("called").fieldNames().forEachRemaining(operation -> {
                operations.add(operation);
                calledSomewhere.add(operation);
            });
            report.path("uncalled").forEach(operation -> operations.add(operation.asText()));
        }
        int documented = operations.size();
        int called = calledSomewhere.size();
        List<String> issues = operations.stream().filter(operation -> !calledSomewhere.contains(operation)).toList();
        if (documented == 0) {
            return QualityDimension.notMeasured(QualityDimension.API_COVERAGE, "no documented operations");
        }
        double share = (double) called / documented;
        List<String> facts = new ArrayList<>();
        facts.add(called + " of " + documented + " documented operations called by the suite"
                + (minimum == null ? "" : " (minimum " + Math.round(minimum * 100) + "%)"));
        list(facts, "never called", issues);
        QualityStatus status = minimum != null && share < minimum ? QualityStatus.FAIL
                : issues.isEmpty() ? QualityStatus.PASS : QualityStatus.WARN;
        return new QualityDimension(QualityDimension.API_COVERAGE, status, (int) Math.round(100 * share),
                "100 × called / documented operations", facts).withIssues(issues);
    }

    /**
     * Declared state-machine transitions no flow took during the run (module-flow's coverage): the
     * branches no test walks. WARN while any is untaken — a gap to read about, not a broken product.
     */
    static QualityDimension flowCoverage(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-flow", "flow-coverage", "flow-coverage.json", mapper);
        if (reports.isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.FLOW_COVERAGE,
                    "no flow-coverage.json (module-flow) in the run");
        }
        // per flow across contexts: a transition one context took is covered
        java.util.Map<String, java.util.Set<String>> declaredByFlow = new java.util.TreeMap<>();
        java.util.Map<String, java.util.Set<String>> takenByFlow = new java.util.TreeMap<>();
        for (JsonNode report : reports) {
            for (JsonNode flow : report.path("flows")) {
                if (flow.path("declared").asInt() == 0) {
                    continue;
                }
                String name = flow.path("flow").asText();
                java.util.Set<String> declared = declaredByFlow.computeIfAbsent(name, k -> new java.util.TreeSet<>());
                java.util.Set<String> taken = takenByFlow.computeIfAbsent(name, k -> new java.util.TreeSet<>());
                // the declared transitions themselves; a report without them still names the untaken ones
                flow.path("transitions").forEach(edge -> declared.add(edge.asText()));
                flow.path("uncovered").forEach(edge -> declared.add(edge.asText()));
                flow.path("taken").forEach(edge -> taken.add(edge.asText()));
                if (!flow.has("transitions")) {
                    // an early layout: taken ∪ uncovered is the declared set, near enough
                    flow.path("taken").forEach(edge -> declared.add(edge.asText()));
                }
            }
        }
        int declared = 0;
        int flows = declaredByFlow.size();
        List<String> issues = new ArrayList<>();
        declaredByFlow.forEach((name, edges) -> {
            edges.stream().filter(edge -> !takenByFlow.get(name).contains(edge))
                    .forEach(edge -> issues.add("flow " + name + " " + edge));
        });
        for (java.util.Set<String> edges : declaredByFlow.values()) {
            declared += edges.size();
        }
        if (declared == 0) {
            return QualityDimension.notMeasured(QualityDimension.FLOW_COVERAGE,
                    "no flow in the run declares its transitions");
        }
        List<String> facts = new ArrayList<>();
        facts.add((declared - issues.size()) + " of " + declared + " declared transitions taken across " + flows
                + " flows");
        list(facts, "never taken", issues);
        return new QualityDimension(QualityDimension.FLOW_COVERAGE, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.WARN, Math.round(100f * (declared - issues.size()) / declared),
                "100 × taken / declared transitions", facts).withIssues(issues);
    }

    /** What the run's mock scopes left over (module-mock's summary); empty without a summary. */
    record MockLeftovers(boolean measured, long scopes, List<String> unusedStubs, List<String> unmatched) {
    }

    static MockLeftovers mockLeftovers(RunIndex index, ObjectMapper mapper) {
        List<JsonNode> reports = reports(index, "module-mock", "mock-summary", "mock-summary.json", mapper);
        long scopes = 0;
        List<String> unused = new ArrayList<>();
        List<String> unmatched = new ArrayList<>();
        for (JsonNode report : reports) {
            scopes += report.path("scopes").asLong();
            report.path("unusedStubs").forEach(stub -> unused.add(stub.path("stub").asText()));
            report.path("unmatchedRequests").forEach(request -> unmatched.add(request.path("request").asText()));
        }
        return new MockLeftovers(!reports.isEmpty(), scopes, List.copyOf(new java.util.LinkedHashSet<>(unused)),
                List.copyOf(new java.util.LinkedHashSet<>(unmatched)));
    }

    /**
     * A security run whose coverage is whole: enabled, measured, not marked
     * incomplete, no {@code not_measured} coverage row, and something actually
     * measured (or an explicit {@code complete} flag).
     */
    private static boolean completeSecurityRun(JsonNode report) {
        if (!report.path("measured").asBoolean() || !report.path("enabled").asBoolean(true)) {
            return false;
        }
        if (report.has("complete") && !report.path("complete").asBoolean()) {
            return false;
        }
        boolean covered = report.has("complete");
        for (JsonNode row : report.path("coverage")) {
            String status = row.path("status").asText();
            if (status.equals("not_measured")) {
                return false;
            }
            covered |= status.equals("measured");
        }
        return covered;
    }

    /**
     * Tests that got markedly slower than in the baseline: at least
     * {@code factor} times and {@code minMillis} longer. Needs a baseline —
     * one run's durations alone say nothing about efficiency.
     */
    static QualityDimension efficiency(List<TestCase> tests, QualityBaseline baseline,
                                       QualityProperties.EfficiencyProperties limits) {
        if (baseline == null || baseline.durations().isEmpty()) {
            return QualityDimension.notMeasured(QualityDimension.EFFICIENCY,
                    "needs a baseline with test durations (writeBaseline()) to compare against");
        }
        List<String> issues = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        int compared = 0;
        for (TestCase test : tests) {
            Long before = baseline.durations().get(test.id());
            if (before == null || test.millis() < 0 || test.outcome() != Outcome.PASSED) {
                continue;
            }
            compared++;
            if (test.millis() >= before * limits.slowdownFactor() && test.millis() - before >= limits.minMillis()) {
                issues.add(test.id());
                if (issues.size() <= LISTED) {
                    facts.add("slower: " + test.id() + " " + before + " ms → " + test.millis() + " ms");
                }
            }
        }
        if (compared == 0) {
            return QualityDimension.notMeasured(QualityDimension.EFFICIENCY,
                    "no passed test of this run is in the baseline");
        }
        facts.addFirst(compared + " tests compared with the baseline, " + issues.size() + " at least "
                + limits.slowdownFactor() + "× and " + limits.minMillis() + " ms slower");
        return new QualityDimension(QualityDimension.EFFICIENCY, issues.isEmpty() ? QualityStatus.PASS
                : QualityStatus.WARN, Math.round(100f * (compared - issues.size()) / compared),
                "100 × (compared − slower) / compared", facts).withIssues(issues);
    }

    /** A report in a layout this module does not know: judged as not measured, never as a zero. */
    private static final class UnsupportedFormat extends RuntimeException {

        UnsupportedFormat(String message) {
            super(message);
        }
    }

    /** The dimension, or NOT MEASURED naming the report whose {@code formatVersion} is unknown. */
    static QualityDimension guarded(String name, java.util.function.Supplier<QualityDimension> dimension) {
        try {
            return dimension.get();
        } catch (UnsupportedFormat e) {
            return QualityDimension.notMeasured(name, e.getMessage());
        }
    }

    /**
     * The producer's report file by its name — not every JSON of the category: a module also
     * registers files of other layouts (db-contract's schema snapshot has a version of its own).
     */
    private static List<JsonNode> reports(RunIndex index, String source, String category, String name,
                                          ObjectMapper mapper) {
        if (index == null) {
            return List.of();
        }
        List<JsonNode> reports = new ArrayList<>();
        for (RunIndex.Artifact artifact : index.artifacts(source, category)) {
            if (!artifact.name().equals(name) && !artifact.name().endsWith("/" + name)) {
                continue;
            }
            Optional<Path> file = index.file(artifact);
            if (file.isEmpty()) {
                continue;
            }
            try {
                JsonNode report = mapper.readTree(file.get().toFile());
                int version = report.path("formatVersion").asInt(FORMAT_VERSION);
                if (version != FORMAT_VERSION) {
                    throw new UnsupportedFormat(source + "'s " + artifact.name() + " has formatVersion " + version
                            + "; this module reads " + FORMAT_VERSION + " — update module-quality with it");
                }
                reports.add(report);
            } catch (IOException e) {
                throw new QualityException("Could not read " + source + "'s " + artifact.name() + " at "
                        + file.get(), e);
            }
        }
        return reports;
    }

    private static void list(List<String> facts, String label, List<String> issues) {
        issues.stream().limit(LISTED).forEach(issue -> facts.add(label + ": " + issue));
        if (issues.size() > LISTED) {
            facts.add("… and " + (issues.size() - LISTED) + " more");
        }
    }
}
