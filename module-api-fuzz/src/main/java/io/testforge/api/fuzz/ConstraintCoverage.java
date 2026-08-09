package io.testforge.api.fuzz;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which of the document's promises this run actually tested.
 *
 * <p>Not a quality score. A percentage would invite comparison between APIs
 * that declare wildly different amounts, and would reward documents for being
 * vague. What a reader needs is the list: these constraints were exercised,
 * these were not, and the second list is where the run is blind.
 */
public record ConstraintCoverage(
        List<DeclaredConstraint> declared,
        List<DeclaredConstraint> exercised,
        List<DeclaredConstraint> unexercised) {

    public ConstraintCoverage {
        declared = List.copyOf(declared == null ? List.of() : declared);
        exercised = List.copyOf(exercised == null ? List.of() : exercised);
        unexercised = List.copyOf(unexercised == null ? List.of() : unexercised);
    }

    public static ConstraintCoverage of(List<DeclaredConstraint> declared, List<FuzzCase> executedCases) {
        Set<DeclaredConstraint> touched = new TreeSet<>();
        executedCases.stream()
                .filter(fuzzCase -> fuzzCase.constraint() != null)
                .forEach(fuzzCase -> touched.add(
                        new DeclaredConstraint(fuzzCase.location(), fuzzCase.constraint())));

        Set<DeclaredConstraint> sortedDeclared = new TreeSet<>(declared);
        // a case can exercise something the inventory did not list — an items
        // type reached through an array, say — so intersect rather than assume
        List<DeclaredConstraint> exercised = sortedDeclared.stream().filter(touched::contains).toList();
        List<DeclaredConstraint> unexercised = sortedDeclared.stream()
                .filter(constraint -> !touched.contains(constraint))
                .toList();

        return new ConstraintCoverage(List.copyOf(sortedDeclared), exercised, unexercised);
    }

    public static ConstraintCoverage none() {
        return new ConstraintCoverage(List.of(), List.of(), List.of());
    }
}
