package io.testforge.api.fuzz;

import io.testforge.api.explorer.RuntimeExchange;

/**
 * The one valid request sent per operation, and what it proved.
 *
 * <p>Executed once per run, not once per case: a hundred cases against one
 * endpoint ask the same question about reachability, and asking it a hundred
 * times would multiply the traffic this module deliberately keeps small.
 */
public record ControlResult(
        ControlOutcome outcome,
        Integer status,
        Long durationMillis,
        String reason) {

    public static ControlResult of(RuntimeExchange exchange) {
        if (!exchange.completed()) {
            return new ControlResult(ControlOutcome.UNREACHABLE, null, exchange.durationMillis(),
                    "the control request did not complete: " + exchange.error());
        }

        int status = exchange.status();
        ControlOutcome outcome = outcomeOf(status);
        return new ControlResult(outcome, status, exchange.durationMillis(), reasonOf(outcome, status));
    }

    /** A control that could not even be built — no valid baseline exists. */
    public static ControlResult notBuilt(String reason) {
        return new ControlResult(ControlOutcome.UNREACHABLE, null, null, reason);
    }

    public boolean conclusive() {
        return outcome.conclusive();
    }

    private static ControlOutcome outcomeOf(int status) {
        if (status >= 200 && status < 300) {
            return ControlOutcome.ACCEPTED;
        }
        if (status >= 500) {
            return ControlOutcome.FAILED;
        }
        if (HttpFacts.infrastructure(status)) {
            return ControlOutcome.BLOCKED;
        }
        if (HttpFacts.validationShaped(status)) {
            return ControlOutcome.REJECTED;
        }
        // any other 4xx — 404, 405, 409 — is about the resource or the route,
        // not about whether this payload is valid
        return ControlOutcome.BLOCKED;
    }

    private static String reasonOf(ControlOutcome outcome, int status) {
        return switch (outcome) {
            case ACCEPTED -> null;
            case REJECTED -> ("the service refused a request the document calls valid (%d); the baseline may be "
                    + "incomplete, or the endpoint needs state this module does not create").formatted(status);
            case BLOCKED -> ("status %d means the request did not reach validation (auth, rate limiting, routing "
                    + "or a redirect)").formatted(status);
            case FAILED -> "the operation returns %d for valid input, independently of any mutation".formatted(status);
            case UNREACHABLE -> "the control request did not complete";
        };
    }
}
