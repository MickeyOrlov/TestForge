package io.testforge.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Database module properties.
 *
 * <pre>
 * forge:
 *   db:
 *     default-datasource: primaryDataSource
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db")
public record DbProperties(String defaultDatasource) {
}
