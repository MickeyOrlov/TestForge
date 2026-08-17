package io.testforge.db;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.schema.SchemaValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MultiDataSourceIsolationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TestForgeCoreAutoConfiguration.class,
                    TestForgeDbAutoConfiguration.class))
            .withPropertyValues(
                    "forge.wait.timeout=1s",
                    "forge.wait.poll-interval=50ms"
            );

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

    @Configuration
    static class TwoDataSourcesConfig {
        @Bean
        @Primary
        DataSource primaryDataSource() {
            return createDataSource("jdbc:h2:mem:tf_primary;DB_CLOSE_DELAY=-1");
        }

        @Bean
        DataSource auditDataSource() {
            return createDataSource("jdbc:h2:mem:tf_audit;DB_CLOSE_DELAY=-1");
        }
    }

    @Configuration
    static class SingleDataSourceConfig {
        @Bean
        DataSource singleDataSource() {
            return createDataSource("jdbc:h2:mem:tf_single;DB_CLOSE_DELAY=-1");
        }
    }

    @Entity
    @Table(name = "t4_entity")
    static class T4Entity {
        @Id
        private Long id;

        @Column(name = "name")
        private String name;
    }

    @Entity
    @Table(name = "t5_entity")
    static class T5Entity {
        @Id
        private Long id;

        @Column(name = "val")
        private String val;
    }

    @Entity
    @Table(name = "t6_entity")
    static class T6Entity {
        @Id
        private Long id;

        @Column(name = "detail")
        private String detail;
    }

    @Entity
    @Table(name = "t8_entity")
    static class T8Entity {
        @Id
        private Long id;

        @Column(name = "data")
        private String data;
    }

    @Test
    void stateOnlyInPrimaryIsObservedThroughPrimary() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DataSource primaryDs = context.getBean("primaryDataSource", DataSource.class);
                    executeSql(primaryDs, "CREATE TABLE t1_primary (id INT PRIMARY KEY, val VARCHAR(50))");
                    executeSql(primaryDs, "INSERT INTO t1_primary VALUES (1, 'primary-data')");

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);
                    long count = dbWaiter.on("primaryDataSource")
                            .awaitRowCount("primary table row count", "SELECT count(*) FROM t1_primary", 1);

                    assertThat(count).isEqualTo(1L);
                });
    }

    @Test
    void primaryStateDoesNotSucceedThroughAudit() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DataSource primaryDs = context.getBean("primaryDataSource", DataSource.class);
                    DataSource auditDs = context.getBean("auditDataSource", DataSource.class);

                    executeSql(primaryDs, "CREATE TABLE t2_isolation (id INT PRIMARY KEY, val VARCHAR(50))");
                    executeSql(primaryDs, "INSERT INTO t2_isolation VALUES (1, 'primary-data')");
                    executeSql(auditDs, "CREATE TABLE t2_isolation (id INT PRIMARY KEY, val VARCHAR(50))");

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);

                    assertThatThrownBy(() -> dbWaiter.on("auditDataSource")
                            .awaitRowCount("audit check for primary table", "SELECT count(*) FROM t2_isolation", 1))
                            .isInstanceOf(ConditionTimeoutException.class)
                            .hasMessageContaining("audit check for primary table");
                });
    }

    @Test
    void stateOnlyInAuditIsObservedThroughAuditAndNotThroughPrimary() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DataSource primaryDs = context.getBean("primaryDataSource", DataSource.class);
                    DataSource auditDs = context.getBean("auditDataSource", DataSource.class);

                    executeSql(auditDs, "CREATE TABLE t3_audit_only (id INT PRIMARY KEY)");
                    executeSql(auditDs, "INSERT INTO t3_audit_only VALUES (1)");
                    executeSql(primaryDs, "CREATE TABLE t3_audit_only (id INT PRIMARY KEY)");

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);

                    long count = dbWaiter.on("auditDataSource")
                            .awaitRowCount("audit count", "SELECT count(*) FROM t3_audit_only", 1);
                    assertThat(count).isEqualTo(1L);

                    assertThatThrownBy(() -> dbWaiter.on("primaryDataSource")
                            .awaitRowCount("primary check for audit table", "SELECT count(*) FROM t3_audit_only", 1))
                            .isInstanceOf(ConditionTimeoutException.class)
                            .hasMessageContaining("primary check for audit table");
                });
    }

    @Test
    void schemaValidatorForPrimaryReportsInSyncWhileForAuditReportsTableNotFound() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DataSource primaryDs = context.getBean("primaryDataSource", DataSource.class);
                    executeSql(primaryDs, "CREATE TABLE t4_entity (id BIGINT PRIMARY KEY, name VARCHAR(255))");

                    SchemaValidator schemaValidator = context.getBean(SchemaValidator.class);

                    List<String> primaryDiff = schemaValidator.forDataSource("primaryDataSource").missingColumns(T4Entity.class);
                    assertThat(primaryDiff).isEmpty();

                    List<String> auditDiff = schemaValidator.forDataSource("auditDataSource").missingColumns(T4Entity.class);
                    assertThat(auditDiff)
                            .hasSize(1)
                            .first()
                            .asString()
                            .contains("table 't4_entity' not found in database");
                });
    }

    @Test
    void defaultResolutionActsOnPrimaryDatabaseWhenOneBeanIsPrimary() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DataSource primaryDs = context.getBean("primaryDataSource", DataSource.class);
                    executeSql(primaryDs, "CREATE TABLE t5_entity (id BIGINT PRIMARY KEY, val VARCHAR(255))");
                    executeSql(primaryDs, "INSERT INTO t5_entity VALUES (1, 'default-primary')");

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);
                    SchemaValidator schemaValidator = context.getBean(SchemaValidator.class);

                    long count = dbWaiter.awaitRowCount("default primary count", "SELECT count(*) FROM t5_entity", 1);
                    assertThat(count).isEqualTo(1L);

                    List<String> diff = schemaValidator.missingColumns(T5Entity.class);
                    assertThat(diff).isEmpty();
                });
    }

    @Test
    void defaultDatasourcePropertyOverridesPrimaryAnnotation() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .withPropertyValues("forge.db.default-datasource=auditDataSource")
                .run(context -> {
                    DataSource auditDs = context.getBean("auditDataSource", DataSource.class);
                    executeSql(auditDs, "CREATE TABLE t6_entity (id BIGINT PRIMARY KEY, detail VARCHAR(255))");
                    executeSql(auditDs, "INSERT INTO t6_entity VALUES (1, 'audit-override')");

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);
                    SchemaValidator schemaValidator = context.getBean(SchemaValidator.class);

                    long count = dbWaiter.awaitRowCount("default audit count", "SELECT count(*) FROM t6_entity", 1);
                    assertThat(count).isEqualTo(1L);

                    List<String> diff = schemaValidator.missingColumns(T6Entity.class);
                    assertThat(diff).isEmpty();
                });
    }

    @Test
    void unknownDatasourceNameFailsWithIllegalArgumentException() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesConfig.class)
                .run(context -> {
                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);
                    SchemaValidator schemaValidator = context.getBean(SchemaValidator.class);

                    assertThatThrownBy(() -> dbWaiter.on("unknownDataSource")
                            .awaitRowCount("unknown check", "SELECT count(*) FROM DUAL", 1))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("unknownDataSource")
                            .hasMessageContaining("primaryDataSource")
                            .hasMessageContaining("auditDataSource");

                    assertThatThrownBy(() -> schemaValidator.forDataSource("unknownDataSource"))
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("unknownDataSource")
                            .hasMessageContaining("primaryDataSource")
                            .hasMessageContaining("auditDataSource");
                });
    }

    @Test
    void singleDataSourceCompatibilityAutoConfiguresWaiterAndValidator() {
        contextRunner
                .withUserConfiguration(SingleDataSourceConfig.class)
        .run(context -> {
            assertThat(context).hasSingleBean(DbWaiter.class);
            assertThat(context).hasSingleBean(SchemaValidator.class);

            DataSource singleDs = context.getBean("singleDataSource", DataSource.class);
            executeSql(singleDs, "CREATE TABLE t8_entity (id BIGINT PRIMARY KEY, data VARCHAR(255))");
            executeSql(singleDs, "INSERT INTO t8_entity VALUES (1, 'single-ds-data')");

            DbWaiter dbWaiter = context.getBean(DbWaiter.class);
            SchemaValidator schemaValidator = context.getBean(SchemaValidator.class);

            long count = dbWaiter.awaitRowCount("single ds wait", "SELECT count(*) FROM t8_entity", 1);
            assertThat(count).isEqualTo(1L);

            List<String> diff = schemaValidator.missingColumns(T8Entity.class);
            assertThat(diff).isEmpty();
        });
    }

    @Test
    void contextWithNoDataSourceBeansHasDbWaiterAndNoSchemaValidator() {
        contextRunner
                .run(context -> {
                    assertThat(context).hasSingleBean(DbWaiter.class);
                    assertThat(context).doesNotHaveBean(SchemaValidator.class);
                    assertThat(context).doesNotHaveBean(DataSourceRegistry.class);

                    DbWaiter dbWaiter = context.getBean(DbWaiter.class);

                    String result = dbWaiter.awaitRow("legacy awaitRow check", () -> Optional.of("ok"));
                    assertThat(result).isEqualTo("ok");

                    assertThatThrownBy(() -> dbWaiter.on("anyDataSource"))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("No DataSourceRegistry is available");
                });
    }
}
