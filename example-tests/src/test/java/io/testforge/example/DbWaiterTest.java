package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.DbWaiter;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates module-db: instead of sleeping and hoping, the test polls for
 * the row that "the backend" (here: a delayed task) writes asynchronously.
 */
@SpringBootTest
class DbWaiterTest {

    @Autowired
    DbWaiter dbWaiter;

    @Autowired
    TaskRecordRepository repository;

    @Test
    void awaitsRowWrittenAsynchronously() {
        String taskId = "task-" + UUID.randomUUID();

        CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS).execute(() -> {
            TaskRecord record = new TaskRecord();
            record.setTaskId(taskId);
            record.setStatus("created");
            repository.save(record);
        });

        TaskRecord found = dbWaiter.awaitRow(
                "task_record with task_id=" + taskId,
                () -> repository.findByTaskId(taskId));

        assertThat(found.getStatus()).isEqualTo("created");
    }
}
