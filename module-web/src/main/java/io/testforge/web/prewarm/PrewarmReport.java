package io.testforge.web.prewarm;

import java.util.List;

/**
 * One prewarm: every visit, or why nothing was visited.
 *
 * @param visits  the visits, in configured order
 * @param millis  the whole prewarm, browser start included
 * @param skipped why nothing ran ({@code null} when it did): disabled, no URLs,
 *                already done in this JVM, or the engine could not start
 */
public record PrewarmReport(List<PrewarmVisit> visits, long millis, String skipped) {

    public PrewarmReport {
        visits = visits == null ? List.of() : List.copyOf(visits);
    }

    static PrewarmReport skipped(String reason) {
        return new PrewarmReport(List.of(), 0, reason);
    }

    /** Whether any configured page answered; {@code false} when visits ran and every one failed or got a 5xx. */
    public boolean environmentReached() {
        return visits.isEmpty() || visits.stream().anyMatch(PrewarmVisit::reached);
    }

    String markdown() {
        StringBuilder out = new StringBuilder("# Prewarm\n\n");
        if (skipped != null) {
            return out.append("Skipped: ").append(skipped).append('\n').toString();
        }
        out.append("- pages: ").append(visits.size()).append('\n')
                .append("- reached: ").append(visits.stream().filter(PrewarmVisit::reached).count()).append('\n')
                .append("- took: ").append(millis).append(" ms\n\n")
                .append("| Page | Status | Ended at | ms | Error |\n| --- | --- | --- | --- | --- |\n");
        for (PrewarmVisit visit : visits) {
            out.append("| ").append(cell(visit.url()))
                    .append(" | ").append(visit.status() == null ? "—" : visit.status())
                    .append(" | ").append(visit.redirected() ? cell(visit.finalUrl()) : "—")
                    .append(" | ").append(visit.millis())
                    .append(" | ").append(visit.error() == null ? "—" : cell(visit.error()))
                    .append(" |\n");
        }
        return out.toString();
    }

    private static String cell(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }
}
