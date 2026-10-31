package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.data.cleanup.CleanupExtension;
import io.testforge.db.state.DbState;
import io.testforge.example.db.TaskRecordRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * A state the API cannot produce quickly, put in place with one insert — and
 * gone after the test, even had it failed, through module-data's Cleanup.
 */
@SpringBootTest
@ExtendWith(CleanupExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DbSetupRowsExampleTest {

    @Autowired
    DbState db;

    @Autowired
    TaskRecordRepository repository;

    private final String taskId = "rows-" + UUID.randomUUID();

    @Test
    void aTaskInAStateTheApiNeverReturnsDirectly() {
        Map<String, Object> key = db.into("task_record").keyedBy("id")
                .insert(Map.of("task_id", taskId, "status", "archived"));

        assertThat(key).containsOnlyKeys("id");
        assertThat(repository.findByTaskId(taskId)).get()
                .satisfies(task -> assertThat(task.getStatus()).isEqualTo("archived"));
    }

    @AfterAll
    void theRowIsGoneAfterTheTest() {
        assertThat(repository.findByTaskId(taskId)).isEmpty();
    }
}
