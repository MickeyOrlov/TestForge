package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.DbWaiter;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.schema.SchemaValidator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Demonstrates named-datasource selection: {@code DbWaiter.on(name)} and
 * {@code SchemaValidator.forDataSource(name)} target a specific database.
 *
 * <p>The primary datasource ({@code jdbc:h2:mem:testforge}) is configured in
 * {@code application.yml}. This test adds a second, independent H2 instance
 * and asserts that each API reaches the intended database.
 */
@SpringBootTest
class MultiDataSourceExampleTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class AuditDataSourceConfig {

        @Bean
        @Primary
        DataSource primaryDataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:testforge;DB_CLOSE_DELAY=-1")
                    .username("sa")
                    .password("")
                    .build();
        }

        @Bean
        DataSource auditDataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:audit;DB_CLOSE_DELAY=-1")
                    .username("sa")
                    .password("")
                    .build();
        }
    }

    /** Minimal entity mapping for the audit table — used only by SchemaValidator. */
    @Entity
    @Table(name = "audit_log")
    static class AuditLogEntry {
        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        private Long id;

        @Column(name = "task_id")
        private String taskId;

        private String action;
    }

    @Autowired
    DbWaiter dbWaiter;

    @Autowired
    SchemaValidator schemaValidator;

    @Autowired
    DataSourceRegistry registry;

    @Autowired
    @Qualifier("auditDataSource")
    DataSource auditDataSource;

    @BeforeEach
    void createAuditSchema() throws Exception {
        try (Connection conn = auditDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id      BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id VARCHAR(255),
                        action  VARCHAR(255)
                    )
                    """);
        }
    }

    @Test
    void awaitRowCountReachesNamedDatabase() throws Exception {
        String taskId = "task-" + UUID.randomUUID();

        try (Connection conn = auditDataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("INSERT INTO audit_log (task_id, action) VALUES ('%s', 'created')"
                    .formatted(taskId));
        }

        long count = dbWaiter.on("auditDataSource").awaitRowCount(
                "audit_log entry for " + taskId,
                "SELECT count(*) FROM audit_log WHERE task_id = '%s'".formatted(taskId),
                1);

        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void schemaValidatorReachesNamedDatabase() {
        assertThat(schemaValidator.forDataSource("auditDataSource")
                .missingColumns(AuditLogEntry.class)).isEmpty();
    }

    @Test
    void registryKnowsBothDatasources() {
        assertThat(registry.names()).contains("auditDataSource");
        assertThat(registry.names().size()).isGreaterThanOrEqualTo(2);
    }
}
