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
 *     schema-foreign-keys: declared   # or all: expect a key behind every join column
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db")
public record DbProperties(String defaultDatasource, String schemaNaming, String schemaForeignKeys) {

    /** {@code all} expects a foreign key behind every join column; anything else only declared ones. */
    public boolean implicitForeignKeys() {
        return "all".equalsIgnoreCase(schemaForeignKeys == null ? null : schemaForeignKeys.trim());
    }
}
