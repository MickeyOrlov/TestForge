package io.testforge.api.fuzz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Chooses which cases actually run, and is the reason this module has a seed.
 *
 * <p>A document with twenty parameters produces far more cases than anyone
 * wants to send at a shared environment, so the matrix is capped. Capping by
 * truncation would mean the last parameters are never tested; capping by
 * sampling means they are, eventually — but only if the sample is reproducible,
 * or a failing run cannot be repeated.
 *
 * <p>Hence: shuffle with a seeded {@link Random}, take the budget, then restore
 * a stable order. Same seed and same document, same cases, same order, same
 * artifact. Change the seed on the next scheduled run and a different slice
 * gets covered.
 */
public class FuzzCaseSelector {

    private final long seed;
    private final int budgetPerOperation;

    public FuzzCaseSelector(long seed, int budgetPerOperation) {
        this.seed = seed;
        this.budgetPerOperation = budgetPerOperation;
    }

    public List<FuzzCase> select(List<FuzzCase> cases) {
        if (cases.size() <= budgetPerOperation) {
            return sorted(cases);
        }

        List<FuzzCase> shuffled = new ArrayList<>(sorted(cases));
        // seeded per operation, so adding an operation to the document does not
        // reshuffle the selection of every other one
        Collections.shuffle(shuffled, new Random(seed + cases.getFirst().operationKey().hashCode()));
        return sorted(shuffled.subList(0, budgetPerOperation));
    }

    /** Replay: exactly the named cases, nothing else. */
    public List<FuzzCase> only(List<FuzzCase> cases, List<String> ids) {
        return sorted(cases.stream().filter(candidate -> ids.contains(candidate.id())).toList());
    }

    private List<FuzzCase> sorted(List<FuzzCase> cases) {
        return cases.stream().sorted(Comparator.comparing(FuzzCase::id)).toList();
    }
}
