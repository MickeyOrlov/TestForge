package io.testforge.quality;

import java.util.List;

/**
 * One side of the product's quality, judged from counted facts.
 *
 * @param name   {@code functional}, {@code stability}, {@code hygiene}, …
 * @param score  0–100 by the dimension's documented formula; {@code null} when not measured
 * @param facts  what the score was computed from, one line each, readable without the report
 */
public record QualityDimension(String name, QualityStatus status, Integer score, String formula, List<String> facts) {

    public static final String FUNCTIONAL = "functional";
    public static final String STABILITY = "stability";
    public static final String HYGIENE = "hygiene";

    public QualityDimension {
        facts = List.copyOf(facts == null ? List.of() : facts);
    }

    static QualityDimension notMeasured(String name, String why) {
        return new QualityDimension(name, QualityStatus.NOT_MEASURED, null, null, List.of(why));
    }
}
