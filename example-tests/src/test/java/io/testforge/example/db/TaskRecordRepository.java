package io.testforge.example.db;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRecordRepository extends JpaRepository<TaskRecord, Long> {

    Optional<TaskRecord> findByTaskId(String taskId);

    default TaskRecord waitByTaskId(String taskId) {
        throw new UnsupportedOperationException("Handled by TestForge repository waiter");
    }
}
