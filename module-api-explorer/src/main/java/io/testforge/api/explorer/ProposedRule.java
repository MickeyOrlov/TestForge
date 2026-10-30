package io.testforge.api.explorer;

/**
 * An extraction rule the document suggests but nobody has configured. It is
 * written to the report as YAML to paste into {@code forge.api-explorer.extract}
 * and is never applied on its own: a wrong guess would send real ids to the
 * wrong endpoint.
 *
 * @param from     producer operationId
 * @param jsonPath where the value sits in the producer's response
 * @param into     consumer target, {@code operationId.parameter}
 * @param reason   why the link looks right, shown next to the proposal
 */
public record ProposedRule(String from, String jsonPath, String into, String reason) {
}
