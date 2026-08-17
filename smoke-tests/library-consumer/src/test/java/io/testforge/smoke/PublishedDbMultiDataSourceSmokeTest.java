package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.DbWaiter;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.schema.SchemaValidator;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Proves that the published module-db jar auto-configures named-datasource
 * selection: registry, DbWaiter.on(name), and SchemaValidator.forDataSource(name)
 * resolve to the correct database.
 */
class PublishedDbMultiDataSourceSmokeTest {

    @Configuration(proxyBeanMethods = false)
    static class TwoDataSourceConfig {

        @Bean
        @Primary
        DataSource primaryDataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:smoke_primary;DB_CLOSE_DELAY=-1")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        DataSource auditDataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:smoke_audit;DB_CLOSE_DELAY=-1")
                    .username("sa")
                    .password("")
                    .build();
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmokeTestApplication.class, TwoDataSourceConfig.class)
            .withPropertyValues("forge.wait.timeout=2s", "forge.wait.poll-interval=50ms");

    @Test
    void registryDiscoversBothDatasources() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DataSourceRegistry.class);
            DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
            assertThat(registry.names()).containsExactly("auditDataSource", "primaryDataSource");
            assertThat(registry.defaultName()).isEqualTo("primaryDataSource");
        });
    }

    @Test
    void dbWaiterOnNamedDatasourceReachesCorrectDatabase() {
        contextRunner.run(context -> {
            DataSource audit = context.getBean("auditDataSource", DataSource.class);
            try (Connection conn = audit.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE smoke_audit (id BIGINT PRIMARY KEY, val VARCHAR(50))");
                stmt.execute("INSERT INTO smoke_audit (id, val) VALUES (1, 'found')");
            }

            DbWaiter dbWaiter = context.getBean(DbWaiter.class);
            long count = dbWaiter.on("auditDataSource").awaitRowCount(
                    "smoke_audit row",
                    "SELECT count(*) FROM smoke_audit",
                    1);
            assertThat(count).isEqualTo(1);
        });
    }

    @Test
    void schemaValidatorForDataSourceResolvesCorrectDatabase() {
        contextRunner.run(context -> {
            DataSource audit = context.getBean("auditDataSource", DataSource.class);
            try (Connection conn = audit.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("CREATE TABLE smoke_schema (id BIGINT PRIMARY KEY, name VARCHAR(100))");
            }

            SchemaValidator validator = context.getBean(SchemaValidator.class);
            // The default datasource (primary) should NOT have smoke_schema table
            assertThat(validator.missingColumns(SmokeSchemaEntity.class))
                    .isNotEmpty();

            // The audit datasource SHOULD have it
            assertThat(validator.forDataSource("auditDataSource")
                    .missingColumns(SmokeSchemaEntity.class)).isEmpty();
        });
    }

    @jakarta.persistence.Entity
    @jakarta.persistence.Table(name = "smoke_schema")
    static class SmokeSchemaEntity {
        @jakarta.persistence.Id
        private Long id;
        private String name;
    }
}
