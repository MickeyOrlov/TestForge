package io.testforge.data;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Test data helper settings.
 *
 * <pre>
 * forge:
 *   data:
 *     max-template-depth: 10
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.data")
public record DataProperties(int maxTemplateDepth) {

    public DataProperties {
        if (maxTemplateDepth <= 0) {
            maxTemplateDepth = 10;
        }
    }
}
