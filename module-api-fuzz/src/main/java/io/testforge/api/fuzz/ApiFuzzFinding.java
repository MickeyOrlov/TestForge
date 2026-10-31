package io.testforge.api.fuzz;

/**
 * A record summarising one failing check.
 *
 * @param operationLabel the operation as the document names it, {@code GET /items/{id}};
 *        a coverage case may send another method ({@code method}) to it
 * @param specId the spec the finding came from; {@code null} until the runner attributes it
 */
public record ApiFuzzFinding(
    String operationLabel,
    String phase,
    String method,
    String path,
    String checkName,
    String message,
    String specId
) {

    public ApiFuzzFinding(
            String operationLabel, String phase, String method, String path, String checkName, String message) {
        this(operationLabel, phase, method, path, checkName, message, null);
    }

    /** {@code not_a_server_error GET /items/{id}}: one failing check on one operation, whatever the case. */
    public String key() {
        return checkName + " " + operationLabel;
    }

    ApiFuzzFinding withSpec(String spec) {
        return new ApiFuzzFinding(operationLabel, phase, method, path, checkName, message, spec);
    }
}
