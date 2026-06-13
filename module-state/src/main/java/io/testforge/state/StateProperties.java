package io.testforge.state;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * State recipes are usually selected from {@code @Prepared(tags = ...)}. The
 * prefix lets teams mix the target state with other tags such as tenant,
 * feature flag or data flavour.
 */
@ConfigurationProperties(prefix = "forge.state")
public record StateProperties(String targetTagPrefix) {

    public StateProperties {
        if (targetTagPrefix == null || targetTagPrefix.isBlank()) {
            targetTagPrefix = "state:";
        }
    }
}
