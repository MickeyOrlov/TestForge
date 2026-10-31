package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.state.DbState;
import io.testforge.db.state.TableSnapshot;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * "The action changed exactly this" in SQL terms: a snapshot of the test's own
 * rows before, a diff after, per column — no entity equals, no repository
 * needed. The repository here stands in for the backend under test.
 */
@SpringBootTest
class TableDiffExampleTest {

    @Autowired
    DbState db;

    @Autowired
    TaskRecordRepository repository;

    private String prefix;

    @BeforeEach
    void tasks() {
        // the test's own rows on a shared database: unique per run, selected by where(...)
        prefix = "tbl-" + UUID.randomUUID() + "-";
        save(prefix + "1", "created");
        save(prefix + "2", "created");
    }

    private TableSnapshot ownTasks() {
        return db.table("task_record").keyedBy("task_id").where("task_id LIKE ?", prefix + "%").take();
    }

    @Test
    void completingATaskChangesOneRowInOneColumn() {
        TableSnapshot before = ownTasks();

        complete(prefix + "1");

        before.diff().expect().added(0).removed(0).changed(1, "status");
    }

    @Test
    void aBackendThatTouchesTheNeighbourIsCaught() {
        TableSnapshot before = ownTasks();

        // the bug: an UPDATE without the right WHERE completes both tasks
        complete(prefix + "1");
        complete(prefix + "2");

        assertThatThrownBy(() -> before.diff().expect().changed(1, "status"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("expected 1 changed rows, got 2")
                .hasMessageContaining("~ task_id=" + prefix + "2 status: created → completed");
    }

    private void save(String taskId, String status) {
        TaskRecord record = new TaskRecord();
        record.setTaskId(taskId);
        record.setStatus(status);
        repository.save(record);
    }

    private void complete(String taskId) {
        TaskRecord record = repository.findByTaskId(taskId).orElseThrow();
        record.setStatus("completed");
        repository.save(record);
    }
}
