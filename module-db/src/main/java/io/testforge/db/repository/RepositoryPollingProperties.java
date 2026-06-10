package io.testforge.db.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in repository wait method support.
 *
 * <pre>
 * forge:
 *   db:
 *     repository-polling:
 *       enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db.repository-polling")
public record RepositoryPollingProperties(boolean enabled) {
}
