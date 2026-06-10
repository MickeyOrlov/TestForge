package io.testforge.db.repository;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Opt-in repository wait method support.
 *
 * <pre>
 * forge:
 *   db:
 *     repository-waiter:
 *       enabled: true
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db.repository-waiter")
public record RepositoryWaiterProperties(boolean enabled) {
}
