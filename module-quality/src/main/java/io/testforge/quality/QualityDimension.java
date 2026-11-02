package io.testforge.quality;

import java.util.List;

/**
 * One side of the product's quality, judged from counted facts.
 *
 * @param name   {@code functional}, {@code stability}, {@code hygiene}, …
 * @param score  0–100 by the dimension's documented formula; {@code null} when not measured
 * @param facts  what the score was computed from, one line each, readable without the report
 * @param issues stable keys of what is wrong — failing test ids, finding keys — compared with the baseline
 * @param delta  this dimension against the baseline; without one, every issue is new
 */
public record QualityDimension(String name, QualityStatus status, Integer score, String formula, List<String> facts,
                               List<String> issues, QualityDelta delta) {

    public static final String FUNCTIONAL = "functional";
    public static final String STABILITY = "stability";
    public static final String HYGIENE = "hygiene";
    public static final String CONTRACTS = "contracts";
    public static final String DATA = "data";
    public static final String ROBUSTNESS = "robustness";
    public static final String AUTHORIZATION = "authorization";
    public static final String EFFICIENCY = "efficiency";
    public static final String SECURITY = "security";
    public static final String API_COVERAGE = "api-coverage";
    public static final String RELIABILITY = "reliability";
    public static final String DB_QUERIES = "db-queries";
    public static final String FLOW_COVERAGE = "flow-coverage";
    public static final String MUTATION = "mutation";
    public static final String DIFF_COVERAGE = "diff-coverage";

    public QualityDimension {
        facts = List.copyOf(facts == null ? List.of() : facts);
        issues = List.copyOf(issues == null ? List.of() : issues);
    }

    public QualityDimension(String name, QualityStatus status, Integer score, String formula, List<String> facts) {
        this(name, status, score, formula, facts, List.of(), null);
    }

    static QualityDimension notMeasured(String name, String why) {
        return new QualityDimension(name, QualityStatus.NOT_MEASURED, null, null, List.of(why));
    }

    QualityDimension withIssues(List<String> keys) {
        return new QualityDimension(name, status, score, formula, facts, keys, delta);
    }

    QualityDimension withDelta(QualityDelta comparison) {
        return new QualityDimension(name, status, score, formula, facts, issues, comparison);
    }

    /** What to do about a dimension that is not PASS, by dimension; facts say what, this says how. */
    private static final java.util.Map<String, String> HINTS = java.util.Map.ofEntries(
            java.util.Map.entry(FUNCTIONAL, "Read each failed test's diagnostics in the run index and fix the product "
                    + "or the test, or accept a known failure in forge.quality.accepted with a ticket and `until`."),
            java.util.Map.entry(STABILITY, "A flaky test passed and failed on the same code: fix the race, shared "
                    + "state or fixed wait it depends on. A rerun hides it, it does not fix it."),
            java.util.Map.entry(CONTRACTS, "The API or an event differs from its contract: fix the producer, or "
                    + "change the contract in a reviewed change."),
            java.util.Map.entry(DATA, "A schema change breaks or risks existing readers: expand, migrate, then "
                    + "contract in a later release."),
            java.util.Map.entry(ROBUSTNESS, "Reproduce each finding group from the fuzz findings.json and fix it, "
                    + "or accept it in the fuzz module's accepted findings with a reason."),
            java.util.Map.entry(AUTHORIZATION, "A role got in or was shut out against the access matrix: fix the "
                    + "endpoint's check, or the matrix in a reviewed change if the rule changed."),
            java.util.Map.entry(EFFICIENCY, "Compare the slower tests and operations with their history and profile "
                    + "the change that slowed them; a new expected speed goes into a reviewed baseline."),
            java.util.Map.entry(SECURITY, "Fix new critical and high findings first; accept one only in "
                    + "module-security's accepted list with a reason and a date."),
            java.util.Map.entry(RELIABILITY, "An operation answered 5xx or failed in transport: read its request in "
                    + "the HTTP log. A server error is a defect even where the test expected it."),
            java.util.Map.entry(DB_QUERIES, "Read the flagged statement shapes in db-queries.json: add the missing "
                    + "WHERE or index, or stop swallowing the failing statement."),
            java.util.Map.entry(API_COVERAGE, "Call each uncalled documented operation from a test, or remove it "
                    + "from the spec if it is gone."),
            java.util.Map.entry(FLOW_COVERAGE, "Add a test that takes each untaken declared transition, or drop the "
                    + "transition from the flow if it cannot happen."),
            java.util.Map.entry(MUTATION, "Each surviving mutant is a change no test noticed: add the assertion "
                    + "that tells the two apart, starting with the classes this change touched."),
            java.util.Map.entry(DIFF_COVERAGE, "Changed lines no test ran: cover them with a test of the "
                    + "behaviour they change, or remove them if nothing needs them."),
            java.util.Map.entry(HYGIENE, "Delete leftover rows, remove unused stubs and match unmatched requests: "
                    + "they hide what a test really depends on."));

    /** How to act on a FAIL or WARN; {@code null} for PASS, NOT MEASURED or a dimension without one. */
    @com.fasterxml.jackson.annotation.JsonProperty("hint")
    public String hint() {
        if (status != QualityStatus.FAIL && status != QualityStatus.WARN) {
            return null;
        }
        if (issues.contains(QualityAssessor.TOO_FEW_TESTS)) {
            return "Fewer tests ran than min-tests: a filter, a tag or a task selected the wrong ones — check what "
                    + "the test step ran before reading the failures."
                    + (issues.size() > 1 ? " " + HINTS.get(name) : "");
        }
        return HINTS.get(name);
    }

    /** The issues the gate fails on: new and not accepted. */
    public List<String> newIssues() {
        return delta == null ? issues : delta.newIssues();
    }
}
