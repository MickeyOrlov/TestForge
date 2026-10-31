package io.testforge.web.prewarm;

/** The environment did not answer a single prewarm page, and {@code fail-when-unreachable} asked to stop. */
public class PrewarmException extends IllegalStateException {

    private final transient PrewarmReport report;

    public PrewarmException(PrewarmReport report) {
        super("No prewarm page answered, so the environment looks down; stopping before the suite runs "
                + "(forge.prewarm.fail-when-unreachable=true):"
                + report.visits().stream().map(visit -> System.lineSeparator() + "  - " + visit.describe())
                        .reduce("", String::concat));
        this.report = report;
    }

    public PrewarmReport report() {
        return report;
    }
}
