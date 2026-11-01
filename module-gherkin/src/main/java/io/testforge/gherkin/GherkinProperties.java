package io.testforge.gherkin;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How {@code module-gherkin} opens each scenario.
 *
 * <pre>
 * forge:
 *   gherkin:
 *     scope-per-scenario: true        # a fresh TEST_SCOPE per scenario (default)
 *     scope-prefix: bdd               # bdd-1a2b3c4d
 *     correlation-per-scenario: true  # a fresh CORRELATION_ID per scenario (default)
 * </pre>
 *
 * The scope is what {@code module-http} writes into requests and
 * {@code module-mock} matches stubs on, so parallel scenarios never answer
 * each other's calls.
 */
@ConfigurationProperties(prefix = "forge.gherkin")
public record GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario) {

    public GherkinProperties {
        if (scopePerScenario == null) {
            scopePerScenario = true;
        }
        if (scopePrefix == null || scopePrefix.isBlank()) {
            scopePrefix = "bdd";
        }
        if (correlationPerScenario == null) {
            correlationPerScenario = true;
        }
    }
}
