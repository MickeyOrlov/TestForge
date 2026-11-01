package io.testforge.security;

import java.util.List;

/** Reviewed operation choices, not observed values. Operation keys are METHOD /templated/path. */
public record BusinessScenarioSeed(
        String id,
        String version,
        BusinessSecuritySuite.Kind kind,
        String setup,
        String probe,
        String ownerRole,
        String actorRole,
        List<String> via,
        String cleanup) {
    public BusinessScenarioSeed {
        id = BusinessSecuritySuite.identifier(id);
        version = BusinessSecuritySuite.identifier(version);
        ownerRole = BusinessSecuritySuite.identifier(ownerRole);
        actorRole = BusinessSecuritySuite.identifier(actorRole);
        if (kind == null) throw new BusinessSecurityException("scenario seed needs a check kind");
        via = List.copyOf(via == null ? List.of() : via);
    }
}
