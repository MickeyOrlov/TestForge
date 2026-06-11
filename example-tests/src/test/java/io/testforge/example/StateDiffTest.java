package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.diff.StateDiff;
import io.testforge.core.diff.StateSnapshot;
import io.testforge.db.DbWaiter;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demonstrates StateSnapshot/StateDiff: side-effect assertions stronger than
 * "the row exists" — after the action exactly one row appeared, none
 * disappeared and none of the neighbours were touched.
 */
@SpringBootTest
class StateDiffTest {

    @Autowired
    TaskRecordRepository repository;

    @Autowired
    DbWaiter dbWaiter;

    @Test
    void exactlyOneRowAppearsAndNeighboursStayUntouched() {
        TaskRecord neighbour = saveTask("neighbour-" + UUID.randomUUID(), "created");
        String newTaskId = "task-" + UUID.randomUUID();

        StateSnapshot<TaskRecord, Long> before =
                StateSnapshot.of(repository.findAll(), TaskRecord::getId);

        // "the backend" writes asynchronously
        CompletableFuture.delayedExecutor(300, TimeUnit.MILLISECONDS)
                .execute(() -> saveTask(newTaskId, "created"));
        dbWaiter.awaitRow("new task_record", () -> repository.findByTaskId(newTaskId));

        StateDiff<TaskRecord> diff = before.diff(repository.findAll(), this::sameContent);

        assertThat(diff.added()).extracting(TaskRecord::getTaskId).containsExactly(newTaskId);
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.changed()).isEmpty();
        assertThat(repository.findByTaskId(neighbour.getTaskId())).isPresent();
    }

    @Test
    void detectsChangedRowUnderSameKey() {
        TaskRecord task = saveTask("task-" + UUID.randomUUID(), "created");

        StateSnapshot<TaskRecord, Long> before =
                StateSnapshot.of(repository.findAll(), TaskRecord::getId);

        task.setStatus("completed");
        repository.save(task);

        StateDiff<TaskRecord> diff = before.diff(repository.findAll(), this::sameContent);

        assertThat(diff.added()).isEmpty();
        assertThat(diff.removed()).isEmpty();
        assertThat(diff.changed()).hasSize(1);
        assertThat(diff.changed().get(0).before().getStatus()).isEqualTo("created");
        assertThat(diff.changed().get(0).after().getStatus()).isEqualTo("completed");
    }

    private TaskRecord saveTask(String taskId, String status) {
        TaskRecord record = new TaskRecord();
        record.setTaskId(taskId);
        record.setStatus(status);
        return repository.save(record);
    }

    private boolean sameContent(TaskRecord a, TaskRecord b) {
        return Objects.equals(a.getTaskId(), b.getTaskId())
                && Objects.equals(a.getStatus(), b.getStatus());
    }
}
