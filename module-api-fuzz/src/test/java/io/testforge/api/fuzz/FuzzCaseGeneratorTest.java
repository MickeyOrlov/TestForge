package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Generation is where schema-awareness lives: a constrained parameter must
 * produce cases that test the constraint, and each case must know what the
 * document implies should happen to it.
 */
class FuzzCaseGeneratorTest {

    private final FuzzCaseGenerator generator = new FuzzCaseGenerator();

    @Test
    void stringConstraintsBecomeBoundaryCasesOnBothSides() {
        List<FuzzCase> cases = generator.generate(FuzzFixtures.operation("getItem"));

        assertThat(cases).extracting(FuzzCase::kind).contains(
                FuzzCaseKind.AT_LOWER_BOUND,
                FuzzCaseKind.TOO_SHORT,
                FuzzCaseKind.AT_UPPER_BOUND,
                FuzzCaseKind.TOO_LONG,
                FuzzCaseKind.PATTERN_VIOLATION,
                FuzzCaseKind.EMPTY_STRING);

        assertThat(value(cases, FuzzCaseKind.AT_UPPER_BOUND)).hasSize(8);
        assertThat(value(cases, FuzzCaseKind.TOO_LONG)).hasSize(9);
        assertThat(value(cases, FuzzCaseKind.AT_LOWER_BOUND)).hasSize(2);
        assertThat(value(cases, FuzzCaseKind.TOO_SHORT)).hasSize(1);
    }

    @Test
    void aValidBoundaryExpectsAcceptanceAndAnInvalidOneExpectsRejection() {
        List<FuzzCase> cases = generator.generate(FuzzFixtures.operation("getItem"));

        assertThat(kind(cases, FuzzCaseKind.AT_UPPER_BOUND).expectation()).isEqualTo(FuzzExpectation.ACCEPT);
        assertThat(kind(cases, FuzzCaseKind.TOO_LONG).expectation()).isEqualTo(FuzzExpectation.REJECT);
        // minLength: 2 is declared, so an empty string is schema-proven invalid
        assertThat(kind(cases, FuzzCaseKind.EMPTY_STRING).expectation()).isEqualTo(FuzzExpectation.REJECT);
    }

    @Test
    void anUnconstrainedStringYieldsProbesRatherThanAccusations() {
        // /search q declares only minLength, so length above and emptiness
        // below are the only things the document actually promises
        List<FuzzCase> cases = forParameter("search", "q");

        assertThat(kind(cases, FuzzCaseKind.TOO_LONG).expectation())
                .describedAs("no maxLength is declared, so a long string breaks no promise")
                .isEqualTo(FuzzExpectation.UNSPECIFIED);
        assertThat(kind(cases, FuzzCaseKind.ENCODING_PROBE).expectation()).isEqualTo(FuzzExpectation.UNSPECIFIED);
        assertThat(kind(cases, FuzzCaseKind.UNICODE).expectation()).isEqualTo(FuzzExpectation.UNSPECIFIED);
    }

    @Test
    void aPatternCaseIsOnlyEmittedWhenTheValueProvablyFailsIt() {
        // itemId's pattern is ^[a-z0-9-]+$, which the old fixed probe string
        // satisfied — v1 accused the service of accepting a value the document
        // allowed
        List<FuzzCase> cases = forParameter("getItem", "itemId");

        assertThat(cases)
                .filteredOn(fuzzCase -> fuzzCase.kind() == FuzzCaseKind.PATTERN_VIOLATION)
                .singleElement()
                .satisfies(fuzzCase -> {
                    assertThat(fuzzCase.value()).doesNotMatch("^[a-z0-9-]+$");
                    assertThat(fuzzCase.expectation()).isEqualTo(FuzzExpectation.REJECT);
                });
    }

    @Test
    void anUnboundedNumberYieldsAProbeNotAViolation() {
        // /search limit declares minimum and maximum, so both are provable...
        assertThat(kind(forParameter("search", "limit"), FuzzCaseKind.ABOVE_MAXIMUM).expectation())
                .isEqualTo(FuzzExpectation.REJECT);

        // ...while an unbounded number can only be probed
        assertThat(forParameter("search", "limit"))
                .extracting(FuzzCase::kind)
                .doesNotContain(FuzzCaseKind.HUGE_NUMBER);
    }

    @Test
    void numericBoundsProduceOffByOneCasesOnEachSide() {
        List<FuzzCase> cases = forParameter("search", "limit");

        assertThat(cases).extracting(FuzzCase::kind, FuzzCase::value).contains(
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.AT_LOWER_BOUND, "1"),
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.BELOW_MINIMUM, "0"),
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.AT_UPPER_BOUND, "100"),
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.ABOVE_MAXIMUM, "101"),
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.FRACTIONAL_FOR_INTEGER, "1.5"),
                org.assertj.core.api.Assertions.tuple(FuzzCaseKind.WRONG_TYPE, "testforge"));
    }

    @Test
    void anEnumProducesOneOutsiderAndNothingElse() {
        List<FuzzCase> cases = forParameter("search", "sort");

        // the enum defines the whole value space; length and encoding cases on
        // top of it would only be noise
        assertThat(cases).singleElement()
                .satisfies(fuzzCase -> assertThat(fuzzCase.kind()).isEqualTo(FuzzCaseKind.ENUM_OUTSIDER));
    }

    @Test
    void aDeclaredFormatProducesAViolationOfThatFormat() {
        assertThat(forParameter("search", "since"))
                .extracting(FuzzCase::kind, FuzzCase::value)
                .contains(org.assertj.core.api.Assertions.tuple(FuzzCaseKind.FORMAT_VIOLATION, "2024-13-45"));
    }

    @Test
    void aRequiredQueryParameterCanBeOmittedButAPathParameterCannot() {
        assertThat(forParameter("search", "q")).extracting(FuzzCase::kind)
                .contains(FuzzCaseKind.OMITTED_REQUIRED);

        // dropping a path parameter would address a different endpoint entirely
        assertThat(forParameter("getItem", "itemId")).extracting(FuzzCase::kind)
                .doesNotContain(FuzzCaseKind.OMITTED_REQUIRED);
    }

    @Test
    void idsAreStableAndReadable() {
        List<FuzzCase> first = generator.generate(FuzzFixtures.operation("getItem"));
        List<FuzzCase> second = generator.generate(FuzzFixtures.operation("getItem"));

        assertThat(first).extracting(FuzzCase::id).isEqualTo(second.stream().map(FuzzCase::id).toList());
        assertThat(first).extracting(FuzzCase::id).contains("getItem/path:itemId/TOO_LONG");
    }

    @Test
    void headerParametersAreLeftToTheEnvironment() {
        assertThat(generator.generate(FuzzFixtures.operation("search")))
                .extracting(FuzzCase::in)
                .containsOnly("query");
    }

    private List<FuzzCase> forParameter(String operationId, String parameterName) {
        return generator.generate(FuzzFixtures.operation(operationId)).stream()
                .filter(fuzzCase -> fuzzCase.parameterName().equals(parameterName))
                .toList();
    }

    private FuzzCase kind(List<FuzzCase> cases, FuzzCaseKind kind) {
        return cases.stream()
                .filter(fuzzCase -> fuzzCase.kind() == kind)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No case of kind " + kind));
    }

    private String value(List<FuzzCase> cases, FuzzCaseKind kind) {
        return kind(cases, kind).value();
    }
}
