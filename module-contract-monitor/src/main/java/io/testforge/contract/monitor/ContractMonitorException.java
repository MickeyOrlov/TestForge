package io.testforge.contract.monitor;

public class ContractMonitorException extends AssertionError {

    private final ContractMonitorReport report;

    public ContractMonitorException(ContractMonitorReport report) {
        super("Contract monitor detected drift. See " + report.reportMarkdown());
        this.report = report;
    }

    public ContractMonitorReport report() {
        return report;
    }
}
