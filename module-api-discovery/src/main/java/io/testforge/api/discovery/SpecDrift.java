package io.testforge.api.discovery;

import io.testforge.contract.json.ContractViolation;
import java.util.List;

/**
 * How far the response drifted from what the document promised, in both
 * directions.
 *
 * <p>{@code violations} is declared-versus-observed: wrong types, missing
 * required fields, enum and range drift. {@code undeclaredPaths} is the other
 * way round: fields the service returns that the document never mentions.
 * Neither direction subsumes the other, which is why both are here.
 */
public record SpecDrift(
        boolean schemaDeclared,
        String schemaRef,
        List<ContractViolation> violations,
        List<String> undeclaredPaths) {

    public SpecDrift {
        violations = List.copyOf(violations == null ? List.of() : violations);
        undeclaredPaths = List.copyOf(undeclaredPaths == null ? List.of() : undeclaredPaths);
    }

    /** The document says nothing about this response — informational, never a failure. */
    public static SpecDrift notDeclared() {
        return new SpecDrift(false, null, List.of(), List.of());
    }

    public boolean empty() {
        return violations.isEmpty() && undeclaredPaths.isEmpty();
    }
}
