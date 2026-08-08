package io.testforge.api.fuzz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The seed only earns its place if it makes a capped run reproducible. These
 * tests are what keep that true.
 */
class FuzzCaseSelectionTest {

    private final List<FuzzCase> all = new FuzzCaseGenerator().generate(FuzzFixtures.operation("search"));

    @Test
    void aBudgetLargerThanTheMatrixKeepsEverything() {
        assertThat(new FuzzCaseSelector(1L, 1000).select(all)).hasSameSizeAs(all);
    }

    @Test
    void theSameSeedSelectsTheSameCasesInTheSameOrder() {
        List<FuzzCase> first = new FuzzCaseSelector(20260101L, 5).select(all);
        List<FuzzCase> second = new FuzzCaseSelector(20260101L, 5).select(all);

        assertThat(first).hasSize(5);
        assertThat(first).extracting(FuzzCase::id).isEqualTo(second.stream().map(FuzzCase::id).toList());
    }

    @Test
    void aDifferentSeedCoversADifferentSlice() {
        List<String> first = new FuzzCaseSelector(1L, 5).select(all).stream().map(FuzzCase::id).toList();
        List<String> second = new FuzzCaseSelector(2L, 5).select(all).stream().map(FuzzCase::id).toList();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void selectionIsOrderedByIdWhateverTheSeedDid() {
        List<FuzzCase> selected = new FuzzCaseSelector(99L, 4).select(all);

        assertThat(selected).extracting(FuzzCase::id).isSorted();
    }

    @Test
    void replayTakesExactlyTheNamedCases() {
        String id = all.getFirst().id();

        List<FuzzCase> replayed = new FuzzCaseSelector(0L, 1).only(all, List.of(id));

        assertThat(replayed).singleElement()
                .satisfies(fuzzCase -> assertThat(fuzzCase.id()).isEqualTo(id));
    }

    @Test
    void replayIgnoresIdsBelongingToAnotherOperation() {
        assertThat(new FuzzCaseSelector(0L, 1).only(all, List.of("getItem/path:itemId/TOO_LONG"))).isEmpty();
    }
}
