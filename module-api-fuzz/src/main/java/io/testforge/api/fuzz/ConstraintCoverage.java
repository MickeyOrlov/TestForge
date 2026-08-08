package io.testforge.api.fuzz;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which of the document's promises this run actually tested — in four layers,
 * because "not tested" hides at least three different situations.
 *
 * <p>Not a quality score. A percentage would invite comparison between APIs that
 * declare wildly different amounts, and would reward documents for being vague.
 * What a reader needs is the lists:
 *
 * <ul>
 *   <li>{@code declared} — every promise the document makes about this
 *       operation's inputs, read from the schema rather than from the cases, so
 *       a constraint that produced no case is still visible.</li>
 *   <li>{@code exercised} — promises a mutation actually attacked.</li>
 *   <li>{@code unsupported} — promises this module decided it cannot test
 *       honestly, each with the reason. This is where the module admits its own
 *       limits rather than approximating around them.</li>
 *   <li>{@code unexercised} — declared, supported, and still untested: the case
 *       budget ran out, or a replay narrowed the run to one case.</li>
 * </ul>
 *
 * <p>{@code outcomes} sits alongside as the fourth layer: not what was tested,
 * but what came back.
 */
public record ConstraintCoverage(
        List<DeclaredConstraint> declared,
        List<DeclaredConstraint> exercised,
        List<UnsupportedConstraint> unsupported,
        List<DeclaredConstraint> unexercised,
        MutationOutcomes outcomes) {

    public ConstraintCoverage {
        declared = List.copyOf(declared == null ? List.of() : declared);
        exercised = List.copyOf(exercised == null ? List.of() : exercised);
        unsupported = List.copyOf(unsupported == null ? List.of() : unsupported);
        unexercised = List.copyOf(unexercised == null ? List.of() : unexercised);
        outcomes = outcomes == null ? MutationOutcomes.none() : outcomes;
    }

    public static ConstraintCoverage of(List<DeclaredConstraint> declared,
                                        List<UnsupportedConstraint> unsupported,
                                        List<FuzzCase> executedCases,
                                        List<FuzzObservation> observations) {

        Set<DeclaredConstraint> touched = new TreeSet<>();
        executedCases.stream()
                // a protocol mutation exercises no declared constraint by
                // definition; counting it here would inflate coverage with work
                // that tested nothing the document promised
                .filter(fuzzCase -> fuzzCase.kind().category() == FuzzCaseCategory.SCHEMA_MUTATION)
                .filter(fuzzCase -> fuzzCase.constraint() != null)
                .forEach(fuzzCase -> touched.add(
                        new DeclaredConstraint(fuzzCase.location(), fuzzCase.constraint())));

        Set<UnsupportedConstraint> sortedUnsupported = new TreeSet<>(unsupported == null ? List.of() : unsupported);
        Set<DeclaredConstraint> blocked = sortedUnsupported.stream()
                .map(UnsupportedConstraint::declared)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        Set<DeclaredConstraint> sortedDeclared = new TreeSet<>(declared);
        // a case can exercise something the inventory did not list — an items
        // type reached through an array, say — so intersect rather than assume
        List<DeclaredConstraint> exercised = sortedDeclared.stream().filter(touched::contains).toList();
        List<DeclaredConstraint> unexercised = sortedDeclared.stream()
                .filter(constraint -> !touched.contains(constraint) && !blocked.contains(constraint))
                .toList();

        return new ConstraintCoverage(List.copyOf(sortedDeclared), exercised,
                List.copyOf(sortedUnsupported), unexercised,
                MutationOutcomes.of(observations == null ? List.of() : observations));
    }

    public static ConstraintCoverage none() {
        return new ConstraintCoverage(List.of(), List.of(), List.of(), List.of(), MutationOutcomes.none());
    }
}
