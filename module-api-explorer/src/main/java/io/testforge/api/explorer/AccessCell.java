package io.testforge.api.explorer;

/**
 * One operation called as one role.
 *
 * @param status   the response status, {@code 0} when nothing came back
 * @param expected the rule it was held to, {@code null} when none is declared
 * @param detail   why it was skipped or failed, else {@code null}
 */
public record AccessCell(String role, int status, String expected, Verdict verdict, String detail) {

    public enum Verdict {
        /** The status matches the declared rule. */
        OK,
        /** It does not: a role got in that should not have, or was shut out of what it should reach. */
        VIOLATION,
        /** Nobody declared what this role should get here. */
        UNDECLARED,
        /** No usable answer: the call failed, or a 5xx said nothing about authorization. */
        ERROR,
        /** Not called: unsafe method, excluded path or a parameter without a value. */
        SKIPPED
    }

    static AccessCell judge(String role, int status, AccessRule rule, String error) {
        String expected = rule == null ? null : rule.text();
        if (error != null) {
            return new AccessCell(role, 0, expected, Verdict.ERROR, error);
        }
        if (rule == null) {
            return new AccessCell(role, status, null, Verdict.UNDECLARED, null);
        }
        if (rule.matches(status)) {
            return new AccessCell(role, status, expected, Verdict.OK, null);
        }
        if (status >= 500) {
            return new AccessCell(role, status, expected, Verdict.ERROR, "server error says nothing about access");
        }
        return new AccessCell(role, status, expected, Verdict.VIOLATION, null);
    }
}
