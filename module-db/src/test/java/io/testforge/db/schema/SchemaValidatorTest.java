package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.datasource.DataSourceRegistry;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Tests {@link SchemaValidator} with real H2 in-memory databases to prove
 * cross-datasource isolation. Two distinct databases are used:
 * <ul>
 *     <li>{@code tf_primary} — contains the {@code orders} table</li>
 *     <li>{@code tf_audit} — contains no {@code orders} table</li>
 * </ul>
 */
class SchemaValidatorTest {

    private static DataSource primaryDs;
    private static DataSource auditDs;

    @Entity
    @Table(name = "orders")
    static class OrderEntity {
        @Id
        private Long id;

        @Column(name = "customer_name")
        private String customerName;

        @Column(name = "total_amount")
        private Long totalAmount;
    }

    @Entity
    @Table(name = "orders")
    static class OrderEntityWithExtra {
        @Id
        private Long id;

        @Column(name = "customer_name")
        private String customerName;

        @Column(name = "total_amount")
        private Long totalAmount;

        @Column(name = "missing_column")
        private String missingColumn;
    }

    @BeforeAll
    static void createDatabases() throws Exception {
        primaryDs = new DriverManagerDataSource(
                "jdbc:h2:mem:tf_primary;DB_CLOSE_DELAY=-1");
        auditDs = new DriverManagerDataSource(
                "jdbc:h2:mem:tf_audit;DB_CLOSE_DELAY=-1");

        try (Connection conn = primaryDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE orders (
                        id BIGINT PRIMARY KEY,
                        customer_name VARCHAR(255),
                        total_amount BIGINT
                    )
                    """);
        }

        // audit database deliberately has NO orders table
        try (Connection conn = auditDs.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE audit_log (
                        id BIGINT PRIMARY KEY,
                        action VARCHAR(255)
                    )
                    """);
        }
    }

    // --- Legacy SchemaValidator(DataSource) path ---

    @Test
    void legacy_inSyncEntity_returnsEmpty() {
        var validator = new SchemaValidator(primaryDs);

        List<String> problems = validator.missingColumns(OrderEntity.class);

        assertThat(problems).isEmpty();
    }

    @Test
    void legacy_missingColumn_reportsIt() {
        var validator = new SchemaValidator(primaryDs);

        List<String> problems = validator.missingColumns(OrderEntityWithExtra.class);

        assertThat(problems).containsExactly("orders.missing_column");
    }

    // --- Registry-backed: default datasource ---

    @Test
    void registryBacked_defaultDatasource_validatesCorrectly() {
        var registry = new DataSourceRegistry(
                Map.of("primary", primaryDs), "primary");

        var validator = new SchemaValidator(registry);

        assertThat(validator.missingColumns(OrderEntity.class)).isEmpty();
    }

    // --- forDataSource: isolation proof ---

    @Test
    void forDataSource_validatesNamedDatabase() {
        Map<String, DataSource> sources = new LinkedHashMap<>();
        sources.put("primary", primaryDs);
        sources.put("audit", auditDs);
        var registry = new DataSourceRegistry(sources, "primary");

        var validator = new SchemaValidator(registry);

        // orders table exists in primary → in sync
        SchemaValidator primaryValidator = validator.forDataSource("primary");
        assertThat(primaryValidator.missingColumns(OrderEntity.class)).isEmpty();

        // orders table does NOT exist in audit → table not found
        SchemaValidator auditValidator = validator.forDataSource("audit");
        List<String> auditProblems = auditValidator.missingColumns(OrderEntity.class);
        assertThat(auditProblems)
                .hasSize(1)
                .first()
                .asString()
                .contains("table 'orders' not found in database");
    }

    // --- forDataSource with null/blank binds default ---

    @Test
    void forDataSource_nullName_bindsDefault() {
        var registry = new DataSourceRegistry(
                Map.of("primary", primaryDs), "primary");
        var validator = new SchemaValidator(registry);

        assertThat(validator.forDataSource(null).missingColumns(OrderEntity.class))
                .isEmpty();
    }

    @Test
    void forDataSource_blankName_bindsDefault() {
        var registry = new DataSourceRegistry(
                Map.of("primary", primaryDs), "primary");
        var validator = new SchemaValidator(registry);

        assertThat(validator.forDataSource("  ").missingColumns(OrderEntity.class))
                .isEmpty();
    }

    // --- forDataSource with unknown name ---

    @Test
    void forDataSource_unknownName_throwsIllegalArgument() {
        var registry = new DataSourceRegistry(
                Map.of("primary", primaryDs), "primary");
        var validator = new SchemaValidator(registry);

        assertThatThrownBy(() -> validator.forDataSource("nonexistent"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent");
    }

    // --- Legacy constructor: forDataSource throws IllegalStateException ---

    @Test
    void legacyConstructor_forDataSource_throwsIllegalState() {
        var validator = new SchemaValidator(primaryDs);

        assertThatThrownBy(() -> validator.forDataSource("anything"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DataSourceRegistry is available")
                .hasMessageContaining("auto-configured SchemaValidator bean");
    }

    // --- forDataSource returns new instance, does not mutate receiver ---

    @Test
    void forDataSource_derivedViewCanBeRetargeted() {
        Map<String, DataSource> sources = new LinkedHashMap<>();
        sources.put("primary", primaryDs);
        sources.put("audit", auditDs);
        var registry = new DataSourceRegistry(sources, "primary");

        // A view must keep the registry, or re-targeting a derived validator
        // fails with "no DataSourceRegistry available".
        SchemaValidator backToPrimary = new SchemaValidator(registry)
                .forDataSource("audit")
                .forDataSource("primary");

        assertThat(backToPrimary.missingColumns(OrderEntity.class)).isEmpty();
    }

    @Test
    void forDataSource_returnsNewInstance() {
        Map<String, DataSource> sources = new LinkedHashMap<>();
        sources.put("primary", primaryDs);
        sources.put("audit", auditDs);
        var registry = new DataSourceRegistry(sources, "primary");

        var original = new SchemaValidator(registry);
        SchemaValidator derived = original.forDataSource("audit");

        assertThat(derived).isNotSameAs(original);
        // original still resolves against default (primary) → in sync
        assertThat(original.missingColumns(OrderEntity.class)).isEmpty();
        // derived resolves against audit → table not found
        assertThat(derived.missingColumns(OrderEntity.class))
                .first()
                .asString()
                .contains("table 'orders' not found in database");
    }
}
