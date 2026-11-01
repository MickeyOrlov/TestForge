package io.testforge.db.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DbQuerySummaryTest {

    private static CapturedQueries.Statement statement(String sql, long millis, String error) {
        return new CapturedQueries.Statement("shop", sql, 1, millis, error, "main", 1L);
    }

    @Test
    void statementsAggregateByShapeWithoutTheirValues() {
        QueryRecorder recorder = new QueryRecorder();
        DbQuerySummary summary = new DbQuerySummary(recorder, null, 100);

        recorder.record(statement("SELECT * FROM orders WHERE id = 1", 5, null));
        recorder.record(statement("SELECT * FROM orders WHERE id = 2", 150, null));
        recorder.record(statement("INSERT INTO customers VALUES ('a@example.test')", 2, "DuplicateKeyException"));
        recorder.record(statement("UPDATE orders SET status = 'X'", 3, null));

        Map<String, Object> json = summary.summary();
        assertThat(json).containsEntry("statements", 4L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shapes = (List<Map<String, Object>>) json.get("shapes");
        assertThat(shapes.getFirst()).containsEntry("shape", "SELECT * FROM orders WHERE id = ?")
                .containsEntry("count", 2L).containsEntry("maxMillis", 150L).containsEntry("slow", true);
        assertThat(shapes).anySatisfy(s -> assertThat(s).containsEntry("errors", 1L)
                .containsEntry("shape", "INSERT INTO customers VALUES (?)"));
        assertThat(shapes).anySatisfy(s -> assertThat(s).containsEntry("unboundedWrite", true));
        assertThat(json.toString()).doesNotContain("a@example.test");
    }
}
