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

    /** The issues the gate fails on: new and not accepted. */
    public List<String> newIssues() {
        return delta == null ? issues : delta.newIssues();
    }
}
