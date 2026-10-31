package io.testforge.api.fuzz;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code report.md}: what a person reads first. One section per spec, one
 * entry per failing check and operation, and the command that reproduces it.
 *
 * <p>Only what TestForge already holds safely goes in: check names, operation
 * labels, Schemathesis's own failure message and the arguments TestForge built
 * — which never carry credentials ({@link SchemathesisCommand}). The target
 * URL is redacted again here. Request headers and bodies are never written.
 */
final class FuzzMarkdownReport {

    static final String FILE_NAME = "report.md";
    static final int MAX_MESSAGE = 300;

    private static final Pattern SHELL_SAFE = Pattern.compile("[A-Za-z0-9_./:=,@%+-]+");

    /** What one spec's run left behind, for the report. */
    record SpecSummary(
            String specId,
            ApiFuzzOutcome outcome,
            int totalScenarios,
            int failedScenarios,
            List<String> errors,
            List<String> command) {

        SpecSummary {
            errors = List.copyOf(errors == null ? List.of() : errors);
            command = command == null ? null : List.copyOf(command);
        }
    }

    private FuzzMarkdownReport() {
    }

    static Path write(Path outputDir, ApiFuzzReport report, List<SpecSummary> specs) {
        try {
            Files.createDirectories(outputDir);
            Path file = outputDir.resolve(FILE_NAME);
            Files.writeString(file, render(report, specs));
            return file;
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to write " + outputDir.resolve(FILE_NAME), e);
        }
    }

    static String render(ApiFuzzReport report, List<SpecSummary> specs) {
        List<ApiFuzzFindingGroup> groups = report.findingGroups();
        StringBuilder out = new StringBuilder("# API Fuzz Report\n\n")
                .append("- outcome: ").append(report.outcome()).append('\n')
                .append("- schemathesis: ").append(report.schemathesisVersion()).append('\n')
                .append("- seed: ").append(report.seed()).append('\n')
                .append("- phases: ").append(String.join(", ", report.phases())).append('\n')
                .append("- scenarios: ").append(report.totalScenarios())
                .append(" (").append(report.failedScenarios()).append(" failed)\n")
                .append("- findings: ").append(report.findings().size()).append(" failing cases in ")
                .append(groups.size()).append(groups.size() == 1 ? " check" : " checks");
        long accepted = groups.stream().filter(ApiFuzzFindingGroup::accepted).count();
        if (accepted > 0) {
            out.append(", ").append(accepted).append(" accepted");
        }
        out.append('\n');
        List<String> stale = report.staleAcceptedFindings();
        if (!stale.isEmpty()) {
            out.append("\n## Accepted findings no longer seen\n\n")
                    .append("Fixed or renamed: remove them from `forge.api-fuzz.accepted-findings`.\n\n");
            stale.forEach(entry -> out.append("- `").append(entry).append("`\n"));
        }
        for (SpecSummary spec : specs) {
            out.append("\n## ").append(spec.specId()).append("\n\n")
                    .append("- outcome: ").append(spec.outcome()).append('\n')
                    .append("- scenarios: ").append(spec.totalScenarios())
                    .append(" (").append(spec.failedScenarios()).append(" failed)\n");
            spec.errors().forEach(error -> out.append("- error: ").append(firstLine(error)).append('\n'));
            List<ApiFuzzFindingGroup> own = groups.stream()
                    .filter(group -> spec.specId().equals(group.specId()))
                    .toList();
            if (own.isEmpty()) {
                continue;
            }
            out.append("\n### Findings\n");
            for (ApiFuzzFindingGroup group : own) {
                out.append("\n#### `").append(group.checkName()).append("` on `").append(group.operationLabel())
                        .append("` (").append(group.occurrences())
                        .append(group.occurrences() == 1 ? " case" : " cases").append(")")
                        .append(group.accepted() ? " — accepted" : "").append("\n\n")
                        .append("- phases: ").append(String.join(", ", group.phases())).append('\n')
                        .append("- methods sent: ").append(String.join(", ", group.methods())).append('\n');
                if (group.firstMessage() != null) {
                    out.append("- first message: ").append(firstLine(group.firstMessage())).append('\n');
                }
                if (spec.command() != null) {
                    out.append("- reproduce:\n\n  ```bash\n  ")
                            .append(reproduce(spec.command(), group.operationLabel()))
                            .append("\n  ```\n");
                }
            }
        }
        return out.toString();
    }

    /**
     * The command this run used, narrowed to one operation: the same seed,
     * phases, methods and generated config, so Schemathesis replays the same
     * cases. Report flags are dropped; the target URL is redacted again.
     */
    static String reproduce(List<String> command, String operationLabel) {
        List<String> args = new ArrayList<>();
        for (int index = 0; index < command.size(); index++) {
            String arg = command.get(index);
            if (("--report".equals(arg) || "--report-dir".equals(arg)) && index + 1 < command.size()) {
                index++;
                continue;
            }
            args.add(arg);
            if (("-u".equals(arg) || "--url".equals(arg)) && index + 1 < command.size()) {
                args.add(UrlRedactor.redact(command.get(++index)));
            }
        }
        if (operationLabel != null) {
            args.add("--include-name");
            args.add(operationLabel);
        }
        return String.join(" ", args.stream().map(FuzzMarkdownReport::quote).toList());
    }

    private static String quote(String arg) {
        if (SHELL_SAFE.matcher(arg).matches()) {
            return arg;
        }
        return "'" + arg.replace("'", "'\"'\"'") + "'";
    }

    private static String firstLine(String text) {
        String line = text.strip().lines().findFirst().orElse("");
        return line.length() > MAX_MESSAGE ? line.substring(0, MAX_MESSAGE) + "..." : line;
    }
}
