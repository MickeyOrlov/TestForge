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
 *     diagnostics:
 *       enabled: true   # default false: wraps every DataSource, like log-sql
 *       last: 20        # statements kept per test thread
 * </pre>
 */
@ConfigurationProperties(prefix = "forge.db")
public record DbProperties(String defaultDatasource, String schemaNaming, String schemaForeignKeys,
                           DiagnosticsProperties diagnostics) {

    public DbProperties {
        if (diagnostics == null) {
            diagnostics = new DiagnosticsProperties(null, null);
        }
    }

    /**
     * Off by default, unlike HTTP diagnostics: recording SQL means wrapping the
     * DataSource bean, and code that casts it to a pool type breaks.
     */
    public record DiagnosticsProperties(Boolean enabled, Integer last) {

        public DiagnosticsProperties {
            if (enabled == null) {
                enabled = false;
            }
            if (last == null || last <= 0) {
                last = 20;
            }
        }
    }

    /** {@code all} expects a foreign key behind every join column; anything else only declared ones. */
    public boolean implicitForeignKeys() {
        return "all".equalsIgnoreCase(schemaForeignKeys == null ? null : schemaForeignKeys.trim());
    }
}
