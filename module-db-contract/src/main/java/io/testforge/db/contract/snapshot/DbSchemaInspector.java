package io.testforge.db.contract.snapshot;

import io.testforge.db.contract.model.DbSchemaSnapshot;
import javax.sql.DataSource;

/**
 * Reads one database schema into TestForge's normalized model.
 *
 * <p>The interface exists so the reading engine stays swappable: TestForge does
 * not write its own JDBC metadata crawler, and it does not let the engine's own
 * model leak into its API either.
 */
@FunctionalInterface
public interface DbSchemaInspector {

    /**
     * Inspects one schema.
     *
     * @param dataSource the datasource to read metadata from
     * @param schema     the schema name to inspect
     * @return a deterministic snapshot of that schema
     * @throws IllegalStateException when the schema cannot be read or does not exist
     */
    DbSchemaSnapshot inspect(DataSource dataSource, String schema);
}
