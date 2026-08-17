package io.testforge.db;

import io.testforge.core.wait.WaitProperties;
import io.testforge.core.wait.Waiter;
import io.testforge.db.datasource.DataSourceRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbWaiterTest {

    private DataSource primaryDs;
    private DataSource auditDs;
    private DataSourceRegistry registry;
    private Waiter fastWaiter;
    private DbWaiter dbWaiter;

    private static DataSource createDataSource(String url) {
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url(url)
                .build();
    }

    private static void executeSql(DataSource ds, String sql) {
        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        } catch (Exception e) {
            throw new RuntimeException("Failed to execute SQL: " + sql, e);
        }
    }

    @BeforeEach
    void setUp() {
        primaryDs = createDataSource("jdbc:h2:mem:dbw_primary_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        auditDs = createDataSource("jdbc:h2:mem:dbw_audit_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");

        executeSql(primaryDs, "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(50))");
        executeSql(auditDs, "CREATE TABLE items (id INT PRIMARY KEY, name VARCHAR(50))");

        Map<String, DataSource> map = Map.of("primary", primaryDs, "audit", auditDs);
        registry = new DataSourceRegistry(map, "primary");

        fastWaiter = new Waiter(new WaitProperties(Duration.ofSeconds(2), Duration.ofMillis(50)));
        dbWaiter = new DbWaiter(fastWaiter, registry);
    }

    @AfterEach
    void tearDown() {
        executeSql(primaryDs, "DROP TABLE items IF EXISTS");
        executeSql(auditDs, "DROP TABLE items IF EXISTS");
    }

    @Test
    void awaitRowCountSeesRowInsertedAsynchronously() {
        CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
            executeSql(primaryDs, "INSERT INTO items (id, name) VALUES (1, 'item-1')");
        });

        long count = dbWaiter.awaitRowCount("items in primary", "SELECT count(*) FROM items", 1);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void onNamedDatasourceTargetsCorrectDatabase() {
        executeSql(primaryDs, "INSERT INTO items (id, name) VALUES (1, 'primary-only')");

        // Primary bound waiter sees the row
        long primaryCount = dbWaiter.on("primary").awaitRowCount("primary items", "SELECT count(*) FROM items", 1);
        assertThat(primaryCount).isEqualTo(1L);

        // Audit bound waiter with a 1s timeout times out because audit has 0 rows
        Waiter shortTimeoutWaiter = new Waiter(new WaitProperties(Duration.ofSeconds(1), Duration.ofMillis(50)));
        DbWaiter shortAuditWaiter = new DbWaiter(shortTimeoutWaiter, registry).on("audit");

        assertThatThrownBy(() -> shortAuditWaiter.awaitRowCount("audit items", "SELECT count(*) FROM items", 1))
                .isInstanceOf(ConditionTimeoutException.class);
    }

    @Test
    void onNullOrBlankResolvesDefaultDatasource() {
        executeSql(primaryDs, "INSERT INTO items (id, name) VALUES (10, 'default-item')");

        assertThat(dbWaiter.on(null).awaitRowCount("null ds", "SELECT count(*) FROM items", 1)).isEqualTo(1L);
        assertThat(dbWaiter.on("").awaitRowCount("empty ds", "SELECT count(*) FROM items", 1)).isEqualTo(1L);
        assertThat(dbWaiter.on("   ").awaitRowCount("blank ds", "SELECT count(*) FROM items", 1)).isEqualTo(1L);
    }

    @Test
    void legacyDbWaiterBehavesAsBeforeAndFailsOnDatasourceMethods() {
        DbWaiter legacyWaiter = new DbWaiter(fastWaiter);

        // Supplier-backed methods work fine
        String result = legacyWaiter.awaitRow("legacy awaitRow", () -> Optional.of("legacy-value"));
        assertThat(result).isEqualTo("legacy-value");

        List<String> list = legacyWaiter.awaitRows("legacy awaitRows", () -> List.of("a", "b"), 2);
        assertThat(list).containsExactly("a", "b");

        // Calling on(...) or awaitRowCount(...) throws IllegalStateException
        assertThatThrownBy(() -> legacyWaiter.on("primary"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DataSourceRegistry is available");

        assertThatThrownBy(() -> legacyWaiter.awaitRowCount("check", "SELECT count(*) FROM items", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No DataSourceRegistry is available");
    }

    @Test
    void onReturnsNewInstanceAndDoesNotMutateReceiver() {
        DbWaiter defaultWaiter = new DbWaiter(fastWaiter, registry);
        DbWaiter auditWaiter = defaultWaiter.on("audit");

        assertThat(auditWaiter).isNotSameAs(defaultWaiter);

        executeSql(primaryDs, "INSERT INTO items (id, name) VALUES (1, 'in-primary')");

        // defaultWaiter still targets primary DB
        assertThat(defaultWaiter.awaitRowCount("default primary", "SELECT count(*) FROM items", 1)).isEqualTo(1L);

        // auditWaiter targets audit DB (0 rows -> times out with short waiter)
        Waiter shortWaiter = new Waiter(new WaitProperties(Duration.ofSeconds(1), Duration.ofMillis(50)));
        DbWaiter shortAudit = defaultWaiter.on("audit");

        // verify shortAudit uses shortWaiter's timing and targets audit
        DbWaiter customAudit = new DbWaiter(shortWaiter, registry).on("audit");
        assertThatThrownBy(() -> customAudit.awaitRowCount("audit items", "SELECT count(*) FROM items", 1))
                .isInstanceOf(ConditionTimeoutException.class);
    }

    @Test
    void unknownDatasourceNameThrowsIllegalArgumentException() {
        DbWaiter unknownWaiter = dbWaiter.on("nonexistent");

        assertThatThrownBy(() -> unknownWaiter.awaitRowCount("nonexistent check", "SELECT count(*) FROM items", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nonexistent")
                .hasMessageContaining("Configured DataSources");
    }

    @Test
    void awaitRowCountHandlesTableCreatedAsynchronously() {
        String tableName = "async_table_" + Math.abs(System.nanoTime());

        // Schedule table creation and insertion after 150ms
        CompletableFuture.delayedExecutor(150, TimeUnit.MILLISECONDS).execute(() -> {
            executeSql(primaryDs, "CREATE TABLE " + tableName + " (id INT PRIMARY KEY)");
            executeSql(primaryDs, "INSERT INTO " + tableName + " VALUES (1)");
        });

        // Initially query fails with SQLException (table not found), but polling continues until table is created
        long count = dbWaiter.awaitRowCount("async table", "SELECT count(*) FROM " + tableName, 1);
        assertThat(count).isEqualTo(1L);
    }
}
