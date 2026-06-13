package io.testforge.contract.monitor;

import java.util.List;

public record ContractMonitorReport(
        boolean enabled,
        String generatedAt,
        boolean healthy,
        List<ContractMonitorCaseReport> cases,
        String outputDir,
        String reportJson,
        String reportMarkdown) {

    public ContractMonitorReport {
        cases = List.copyOf(cases == null ? List.of() : cases);
    }

    public List<ContractMonitorCaseReport> failingCases() {
        return cases.stream()
                .filter(ContractMonitorCaseReport::failed)
                .toList();
    }
}
