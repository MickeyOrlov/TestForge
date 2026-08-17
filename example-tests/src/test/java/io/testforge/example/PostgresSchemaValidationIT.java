package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.schema.SchemaValidator;
import io.testforge.example.db.HealthyPostgresRecord;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Same example suite, real database: the H2 examples prove the logic, this
 * one proves it against the vendor the services actually run on.
 *
 * <p>Tagged {@code containers} and excluded from the default build — run with
 * {@code ./gradlew :example-tests:containersTest} when Docker is available.
 */
@SpringBootTest
@Tag("containers")
@Testcontainers
class PostgresSchemaValidationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    SchemaValidator schemaValidator;

    @Autowired
    TaskRecordRepository repository;

    @Autowired
    DataSource dataSource;

    @Table(name = "pg_type_drift_sample")
    static class PostgresTypeDriftEntity {
        @Id
        private Long id;

        @Column(name = "task_id")
        private String taskId;
    }

    @Table(name = "pg_nullability_drift_sample")
    static class PostgresNullabilityDriftEntity {
        @Id
        private Long id;

        @Column(name = "required_code", nullable = false)
        private String requiredCode;
    }

    @BeforeEach
    void setUpDriftTables() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS pg_type_drift_sample (id BIGINT PRIMARY KEY, task_id INT)");
            stmt.execute("CREATE TABLE IF NOT EXISTS pg_nullability_drift_sample (id BIGINT PRIMARY KEY, required_code VARCHAR(255) NULL)");
        }
    }

    @Test
    void entityMappingMatchesRealPostgresSchema() {
        assertThat(schemaValidator.missingColumns(TaskRecord.class)).isEmpty();
    }

    @Test
    void repositoryRoundTripWorksOnPostgres() {
        String taskId = "task-" + UUID.randomUUID();
        TaskRecord record = new TaskRecord();
        record.setTaskId(taskId);
        record.setStatus("created");
        repository.save(record);

        assertThat(repository.findByTaskId(taskId))
                .isPresent()
                .get()
                .extracting(TaskRecord::getStatus)
                .isEqualTo("created");
    }

    @Test
    void healthyPostgresSchema_reportsNoDriftFromAnyMethod() {
        assertThat(schemaValidator.missingColumns(HealthyPostgresRecord.class)).isEmpty();
        assertThat(schemaValidator.typeDrift(HealthyPostgresRecord.class)).isEmpty();
        assertThat(schemaValidator.nullabilityDrift(HealthyPostgresRecord.class)).isEmpty();
        assertThat(schemaValidator.schemaDrift(HealthyPostgresRecord.class)).isEmpty();

        assertThat(schemaValidator.missingColumns(TaskRecord.class)).isEmpty();
        assertThat(schemaValidator.typeDrift(TaskRecord.class)).isEmpty();
        assertThat(schemaValidator.nullabilityDrift(TaskRecord.class)).isEmpty();
        assertThat(schemaValidator.schemaDrift(TaskRecord.class)).isEmpty();
    }

    @Test
    void booleanColumnMappedToPostgresBool_producesNoTypeDrift_whenPostgresReportsBit() {
        assertThat(schemaValidator.typeDrift(HealthyPostgresRecord.class)).isEmpty();
    }

    @Test
    void characterAndTimestampFamilies_produceNoTypeDriftOnPostgres() {
        assertThat(schemaValidator.typeDrift(HealthyPostgresRecord.class)).isEmpty();
    }

    @Test
    void typeDrift_reportsFamilyMismatchOnPostgres_withBothSidesNamed() {
        List<String> findings = schemaValidator.typeDrift(PostgresTypeDriftEntity.class);

        assertThat(findings)
                .hasSize(1)
                .first()
                .asString()
                .contains("pg_type_drift_sample.task_id")
                .contains("mapped as CHARACTER (String)")
                .contains("database column is INTEGER");
    }

    @Test
    void nullabilityDrift_reportsNullablePostgresColumn_whenMappingDeclaresNotNull() {
        List<String> findings = schemaValidator.nullabilityDrift(PostgresNullabilityDriftEntity.class);

        assertThat(findings)
                .containsExactly("pg_nullability_drift_sample.required_code: mapping declares NOT NULL but database column is nullable");
    }
}

