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
                                      FindingSignature original) {

        if (properties.confirmationRuns() <= 0) {
            return ConfirmationResult.notConfirmed();
        }
        if (!repeatable(operation)) {
            return ConfirmationResult.notAttempted(
                    "%s is not safe to repeat; set forge.api-fuzz.allow-unsafe-confirmation to confirm it"
                            .formatted(operation.method()));
        }

        int attempts = properties.confirmationRuns();
        int matches = 0;
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (FindingSignature.of(observe(operation, control, fuzzCase, request)).matches(original)) {
                matches++;
            }
        }
        return ConfirmationResult.of(attempts, matches);
    }

    /**
     * Runs one request and classifies it exactly as the original case was, so
     * the comparison is between like and like.
     */
    FuzzObservation observe(ExplorableOperation operation, ControlResult control,
                            FuzzCase fuzzCase, PreparedRequest request) {
        RuntimeExchange exchange;
        try {
            exchange = executor.execute(request);
        } catch (RuntimeException e) {
            exchange = RuntimeExchange.failed(Map.of(), e.toString(), 0L);
        }

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
