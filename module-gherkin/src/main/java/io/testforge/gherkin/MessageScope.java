package io.testforge.gherkin;

import io.testforge.core.context.ScenarioContext;
import io.testforge.core.context.ScenarioKeys;
import java.util.Optional;

/**
 * The header a message step filters on with {@code forge.gherkin.scope-messages}:
 * the broker's publish scope header, carrying this scenario's scope.
 */
public final class MessageScope {

    /** A header name and the value this scenario's messages carry in it. */
    public record Header(String name, String value) {
    }

    private MessageScope() {
    }

    /**
     * @param scopeHeader the broker module's {@code publish.scope-header}, or {@code null}
     * @param setting     its property name, for the failure when it is missing
     */
    public static Optional<Header> of(GherkinProperties properties, String scopeHeader, String setting) {
        if (!properties.scopeMessages()) {
            return Optional.empty();
        }
        if (scopeHeader == null || scopeHeader.isBlank()) {
            throw new IllegalStateException("forge.gherkin.scope-messages=true needs " + setting
                    + ": the header the scenario's scope travels in");
        }
        String scope = ScenarioContext.find(ScenarioKeys.TEST_SCOPE).orElseThrow(() -> new IllegalStateException(
                "forge.gherkin.scope-messages=true needs a scenario scope (forge.gherkin.scope-per-scenario=true)"));
        return Optional.of(new Header(scopeHeader, scope));
    }
}
