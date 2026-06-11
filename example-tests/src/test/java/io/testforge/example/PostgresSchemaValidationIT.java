package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.schema.SchemaValidator;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.UUID;
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
}
