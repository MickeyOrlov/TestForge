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
 *     settle: 2s                      # how long "exactly one" and "no message" steps keep watching
 * </pre>
 *
 * The scope is what {@code module-http} writes into requests and
 * {@code module-mock} matches stubs on, so parallel scenarios never answer
 * each other's calls.
 */
@ConfigurationProperties(prefix = "forge.gherkin")
public record GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario,
                                java.time.Duration settle) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
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
        if (settle == null || settle.isNegative() || settle.isZero()) {
            settle = java.time.Duration.ofSeconds(2);
        }
    }

    public GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario) {
        this(scopePerScenario, scopePrefix, correlationPerScenario, null);
    }
}
