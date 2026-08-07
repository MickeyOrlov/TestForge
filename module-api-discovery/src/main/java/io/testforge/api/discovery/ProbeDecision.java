package io.testforge.api.discovery;

/**
 * Whether one endpoint may be called, and if not, why.
 *
 * <p>Written into {@code catalog.json} for every endpoint — including the ones
 * that were called — so the artifact answers "what did this run do, and what
 * did it deliberately not do" without anyone reading the logs.
 */
public record ProbeDecision(
        boolean allowed,
        SkipReason skipReason,
        String detail,
        ResolvedParameters parameters) {

    public static ProbeDecision allow(ResolvedParameters parameters) {
        return new ProbeDecision(true, null, null, parameters);
    }

    public static ProbeDecision skip(SkipReason reason, ResolvedParameters parameters) {
        return new ProbeDecision(false, reason, reason.description(), parameters);
    }

    public static ProbeDecision skip(SkipReason reason, String detail, ResolvedParameters parameters) {
        return new ProbeDecision(false, reason, "%s: %s".formatted(reason.description(), detail), parameters);
    }
}
