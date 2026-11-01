package io.testforge.api.fuzz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code findings.json}: the finding groups of {@code report.md} for a
 * machine — {@code module-quality} reads it for its robustness dimension.
 * The same content as the Markdown: check names, operation labels, counts
 * and whether a group is accepted; never a request, a body or a URL.
 */
final class FuzzFindingsJson {

    static final String FILE_NAME = "findings.json";

    record Group(String specId, String key, String checkName, String operationLabel, int occurrences,
                 boolean accepted) {
    }

    record Findings(String runId, ApiFuzzOutcome outcome, List<Group> groups, List<String> staleAccepted) {
    }

    private FuzzFindingsJson() {
    }

    static Path write(Path outputDir, ApiFuzzReport report) {
        Path file = outputDir.resolve(FILE_NAME);
        List<Group> groups = report.findingGroups().stream()
                .map(g -> new Group(g.specId(), g.key(), g.checkName(), g.operationLabel(), g.occurrences(),
                        g.accepted()))
                .toList();
        try {
            Files.createDirectories(outputDir);
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(file.toFile(),
                    new Findings(report.runId(), report.outcome(), groups, report.staleAcceptedFindings()));
            return file;
        } catch (IOException e) {
            throw new ApiFuzzException("Failed to write " + file, e);
        }
    }
}
