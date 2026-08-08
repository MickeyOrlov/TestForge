package io.testforge.api.fuzz;

import io.testforge.api.explorer.ExplorableOperation;
import io.testforge.api.explorer.ExchangeExecutor;
import io.testforge.api.explorer.PreparedRequest;
import io.testforge.api.explorer.RuntimeExchange;
import java.util.Map;

/**
 * Sends a finding's request again, a bounded number of times, and reports how
 * often the same defect came back.
 *
 * <p>Off by default. Confirmation costs real requests against somebody's
 * environment, and a module that quietly triples its traffic the moment it
 * finds something is a module people switch off.
 *
 * <p>Write methods are refused unless a project opts in separately. Repeating a
 * {@code POST} may create a second resource, and the existing safety gates say
 * nothing about repetition — sending a request once because two keys were
 * turned is not consent to send it three more times.
 */
public class FindingConfirmer {

    private final ExchangeExecutor executor;
    private final ResponseClassifier classifier;
    private final ApiFuzzProperties properties;

    public FindingConfirmer(ExchangeExecutor executor, ResponseClassifier classifier,
                            ApiFuzzProperties properties) {
        this.executor = executor;
        this.classifier = classifier;
        this.properties = properties;
    }

    public ConfirmationResult confirm(ExplorableOperation operation, ControlResult control,
                                      FuzzCase fuzzCase, PreparedRequest request,
                                      PreparedRequest controlRequest, FindingSignature original) {

        if (properties.confirmationRuns() <= 0) {
            return ConfirmationResult.notConfirmed();
        }
        if (!repeatable(operation)) {
            return ConfirmationResult.notAttempted(
                    "%s is not safe to repeat; set forge.api-fuzz.allow-unsafe-confirmation to confirm it"
                            .formatted(operation.method()));
        }

        int planned = properties.confirmationRuns();
        int attempts = 0;
        int matches = 0;

        for (int attempt = 0; attempt < planned; attempt++) {
            attempts++;
            if (FindingSignature.of(observe(operation, control, fuzzCase, request)).matches(original)) {
                matches++;
            }

            // the mutant may have changed the backend underneath us — a created
            // resource, a tripped circuit breaker, a filled quota. Re-checking
            // the control catches that; without it a poisoned environment turns
            // into a confident FLAKY or DISAPPEARED that says nothing about the
            // defect
            if (attempt < planned - 1) {
                ControlResult recheck = ControlResult.of(send(controlRequest));
                if (!recheck.conclusive()) {
                    return ConfirmationResult.of(attempts, matches,
                            ("the control stopped being accepted after %d attempt(s) (%s); the environment "
                                    + "changed underneath the confirmation").formatted(attempts, recheck.outcome()));
                }
            }
        }
        return ConfirmationResult.of(attempts, matches);
    }

    private RuntimeExchange send(PreparedRequest request) {
        try {
            return executor.execute(request);
        } catch (RuntimeException e) {
            return RuntimeExchange.failed(Map.of(), e.toString(), 0L);
        }
    }

    /**
     * Runs one request and classifies it exactly as the original case was, so
     * the comparison is between like and like.
     */
    FuzzObservation observe(ExplorableOperation operation, ControlResult control,
                            FuzzCase fuzzCase, PreparedRequest request) {
        RuntimeExchange exchange = send(request);

        ResponseClassifier.Classification classification =
                classifier.classify(operation, control, fuzzCase, exchange);

        return new FuzzObservation(fuzzCase, null, classification.verdict(), fuzzCase.expectation(),
                exchange.completed() ? exchange.status() : null, exchange.contentType(), null,
                exchange.durationMillis(), classification.evidence(), classification.reason(),
                classification.mismatches(), exchange.completed() ? null : exchange.error(), null,
                ConfirmationResult.notConfirmed(), ShrinkOutcome.notAttempted());
    }

    /** Safe methods repeat freely; everything else needs its own opt-in. */
    boolean repeatable(ExplorableOperation operation) {
        return io.testforge.api.explorer.ApiExplorerProperties.SAFE_METHODS.contains(operation.method())
                || properties.allowUnsafeConfirmation();
    }
}
