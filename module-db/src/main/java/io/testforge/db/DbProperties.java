package io.testforge.db;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Database module properties.
 *
 * <pre>
 * forge:
 *   db:
 *     default-datasource: primaryDataSource
 *     schema-naming: snake-case   # or as-is; implicit names in SchemaValidator
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db")
public record DbProperties(String defaultDatasource, String schemaNaming) {
}
