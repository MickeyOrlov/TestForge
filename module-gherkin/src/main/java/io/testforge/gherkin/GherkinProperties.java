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
 *     scope-messages: false           # true: message steps see only messages with this scenario's scope header
 * </pre>
 *
 * {@code scope-messages} needs a system that copies the scope header from the
 * command it received into the events it sends; without that, filter message
 * steps by a run-unique value ({@code ${orderId}}) instead.
 *
 * The scope is what {@code module-http} writes into requests and
 * {@code module-mock} matches stubs on, so parallel scenarios never answer
 * each other's calls.
 */
@ConfigurationProperties(prefix = "forge.gherkin")
public record GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario,
                                java.time.Duration settle, Boolean scopeMessages) {

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
        if (scopeMessages == null) {
            scopeMessages = false;
        }
    }

    public GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario,
                             java.time.Duration settle) {
        this(scopePerScenario, scopePrefix, correlationPerScenario, settle, null);
    }

    public GherkinProperties(Boolean scopePerScenario, String scopePrefix, Boolean correlationPerScenario) {
        this(scopePerScenario, scopePrefix, correlationPerScenario, null, null);
    }
}
