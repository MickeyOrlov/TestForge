package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.schema.SchemaValidator;
import io.testforge.example.db.TaskRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates schema-drift detection: every column mapped by the entity must
 * exist in the real database. Run this kind of test in a scheduled CI job
 * against each environment to catch service migrations that silently break the
 * test framework's mappings.
 */
@SpringBootTest
class SchemaValidatorTest {

    @Autowired
    SchemaValidator schemaValidator;

    @Test
    void entityMappingMatchesDatabaseSchema() {
        assertThat(schemaValidator.missingColumns(TaskRecord.class)).isEmpty();
    }
}
