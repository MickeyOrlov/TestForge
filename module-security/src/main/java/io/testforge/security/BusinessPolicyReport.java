package io.testforge.security;

import java.util.List;

/** Reasons are fixed diagnostics and structural policy aliases, never request/response data. */
public record BusinessPolicyReport(
        int formatVersion,
        String suite,
        String policyVersion,
        Status status,
        List<CaseResult> cases,
        List<Issue> issues) {
    public enum Status {
        VALID,
        REVIEW_REQUIRED,
        INVALID
    }

    public record Issue(
            String caseId,
            String stepId,
            Status status,
            String code,
            String reason,
            String location) {
        public Issue(String caseId, String stepId, Status status, String code, String reason) {
            this(caseId, stepId, status, code, reason, null);
        }
    }

    public record CaseResult(String id, Status status) {}

    public BusinessPolicyReport {
        cases = List.copyOf(cases);
        issues = List.copyOf(issues);
    }
}
