package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.db.query.CapturedQueries;
import io.testforge.db.query.DbQueries;
import io.testforge.example.db.TaskRecord;
import io.testforge.example.db.TaskRecordRepository;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * What SQL an action ran. Loading ten tasks one lookup at a time is the same
 * statement ten times — the N+1 a lazy mapping or a loop over a repository
 * produces — and a query budget catches it before production traffic does.
 */
@SpringBootTest(properties = "forge.db.queries.enabled=true")
class QueryBudgetExampleTest {

    @Autowired
    DbQueries queries;

    @Autowired
    TaskRecordRepository repository;

    private List<String> ids;

    @BeforeEach
    void tasks() {
        String prefix = "qb-" + UUID.randomUUID() + "-";
        ids = IntStream.range(0, 10).mapToObj(i -> prefix + i).toList();
        ids.forEach(id -> {
            TaskRecord record = new TaskRecord();
            record.setTaskId(id);
            record.setStatus("created");
            repository.save(record);
        });
    }

    @Test
    void loadingTheTasksInOneQueryFitsTheBudget() {
        CapturedQueries sql = queries.during(() -> repository.findAll());

        sql.expect().atMost(1).none(CapturedQueries.Kind.UPDATE);
    }

    @Test
    void loadingThemOneByOneIsCaughtAsAnNPlusOne() {
        // the bug: a loop over a repository, one SELECT per task
        CapturedQueries sql = queries.during(() -> ids.forEach(repository::findByTaskId));

        assertThatThrownBy(() -> sql.expect().noRepeated(3))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("statements repeated more than 3 times (N+1?)")
                .hasMessageContaining("10 × ");
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void aMassUpdateAndAWholeTableReadAreFlagged() {
        // a table of this example's own: the mass update must not reach other examples' rows
        jdbc.execute("CREATE TABLE IF NOT EXISTS qb_tasks (id BIGINT PRIMARY KEY, status VARCHAR(16))");
        CapturedQueries sql = queries.during(() -> {
            // the bug: "complete my tasks" without the owner filter, then a page that loads everything
            jdbc.update("UPDATE qb_tasks SET status = 'completed'");
            repository.findAll();
        });

        assertThatThrownBy(() -> sql.expect().noUnboundedWrites())
                .hasMessageContaining("writes without a WHERE (every row of the table): "
                        + "[1 × UPDATE qb_tasks SET status = ?]");
        assertThatThrownBy(() -> sql.expect().noUnfilteredReads())
                .hasMessageContaining("reads of a whole table (no WHERE or LIMIT)");
    }

    @Test
    void aFailureTheApplicationSwallowedIsStillSeen() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS qb_customers (email VARCHAR(96) PRIMARY KEY)");
        String email = UUID.randomUUID() + "@example.test";
        jdbc.update("INSERT INTO qb_customers VALUES (?)", email);

        CapturedQueries sql = queries.during(() -> {
            // "register" catches the duplicate key and answers 200 as if nothing happened
            try {
                jdbc.update("INSERT INTO qb_customers VALUES (?)", email);
            } catch (org.springframework.dao.DuplicateKeyException ignored) {
                // swallowed by the code under test
            }
        });

        sql.expect().noneSlowerThan(java.time.Duration.ofSeconds(5));
        assertThatThrownBy(() -> sql.expect().noErrors())
                .hasMessageContaining("statements failed inside the action")
                .hasMessageContaining("INSERT INTO qb_customers VALUES (?)");
    }
}
