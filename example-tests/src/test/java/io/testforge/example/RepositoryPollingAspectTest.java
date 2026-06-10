package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates the opt-in repository waiter: a waitBy... repository method is
 * routed to the matching findBy... query and polled until a row appears.
 */
@SpringBootTest
class RepositoryPollingAspectTest {

    @Autowired
    TaskRecordRepository repository;

    @Test
    void waitsThroughRepositoryNamingConvention() {
        String taskId = "task-" + UUID.randomUUID();

        CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS).execute(() -> {
            TaskRecord record = new TaskRecord();
            record.setTaskId(taskId);
            record.setStatus("processed");
            repository.save(record);
        });

        TaskRecord found = repository.waitByTaskId(taskId);

        assertThat(found.getStatus()).isEqualTo("processed");
    }
}
