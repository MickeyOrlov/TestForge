package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class TableSnapshotTest {

    private DriverManagerDataSource dataSource;
    private DbState db;

    @BeforeEach
    void schema() throws SQLException {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        sql("CREATE TABLE orders (id INT PRIMARY KEY, customer_id VARCHAR(10), status VARCHAR(20), "
                + "total DECIMAL(10,2), updated_at TIMESTAMP)");
        sql("INSERT INTO orders VALUES (1, 'c-1', 'NEW', 10.00, NULL), (2, 'c-1', 'NEW', 5.00, NULL), "
                + "(3, 'c-2', 'NEW', 7.00, NULL)");
    }

    private void sql(String statement) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement s = connection.createStatement()) {
            s.execute(statement);
        }
    }

    private TableSnapshot customerOne() {
        return db.table("orders").keyedBy("id").where("customer_id = ?", "c-1").take();
    }

    @Test
    void addedRemovedAndChangedRowsAreToldApartPerColumn() throws SQLException {
        TableSnapshot before = customerOne();
        sql("UPDATE orders SET status = 'PAID' WHERE id = 1");
        sql("DELETE FROM orders WHERE id = 2");
        sql("INSERT INTO orders VALUES (4, 'c-1', 'NEW', 3.50, NULL)");

        TableDiff diff = before.diff();

        assertThat(diff.added()).singleElement().satisfies(row -> assertThat(row).containsEntry("id", 4));
        assertThat(diff.removed()).singleElement().satisfies(row -> assertThat(row).containsEntry("id", 2));
        assertThat(diff.changed()).singleElement().satisfies(change -> {
            assertThat(change.key()).containsEntry("id", 1);
            assertThat(change.columns()).containsOnlyKeys("status");
            assertThat(change.columns().get("status")).isEqualTo(new TableDiff.ValueChange("NEW", "PAID"));
        });
        assertThat(diff.toString()).isEqualTo("""
                orders where customer_id = ? (keyed by id)
                  + id=4 {customer_id=c-1, status=NEW, total=3.5, updated_at=null}
                  ~ id=1 status: NEW → PAID
                  - id=2 {customer_id=c-1, status=NEW, total=5, updated_at=null}""");
    }

    @Test
    void rowsOutsideTheWhereClauseAreNotTheTestsBusiness() throws SQLException {
        TableSnapshot before = customerOne();
        sql("UPDATE orders SET status = 'PAID' WHERE id = 3");

        before.diff().expect().nothing();
    }

    @Test
    void anUpdateOfANeighbouringColumnFailsWithTheWholeDiff() throws SQLException {
        TableSnapshot before = customerOne();
        sql("UPDATE orders SET status = 'PAID', total = 0 WHERE id = 1");

        assertThatThrownBy(() -> before.diff().expect().added(0).removed(0).changed(1, "status"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("columns changed that should not have: [total]")
                .hasMessageContaining("~ id=1 status: NEW → PAID; total: 10 → 0");
    }

    @Test
    void ignoredColumnsAndEqualDecimalsAreNotChanges() throws SQLException {
        TableSnapshot before = db.table("orders").keyedBy("id").where("customer_id = ?", "c-1")
                .ignoring("updated_at").take();
        sql("UPDATE orders SET updated_at = CURRENT_TIMESTAMP, total = 10.0 WHERE id = 1");

        before.diff().expect().nothing();
    }

    @Test
    void aCompositeKeyIdentifiesRows() throws SQLException {
        sql("CREATE TABLE order_lines (order_id INT, line_no INT, qty INT, PRIMARY KEY (order_id, line_no))");
        sql("INSERT INTO order_lines VALUES (1, 1, 2), (1, 2, 1)");
        TableSnapshot before = db.table("order_lines").keyedBy("order_id", "line_no").take();
        sql("UPDATE order_lines SET qty = 5 WHERE order_id = 1 AND line_no = 2");

        TableDiff diff = before.diff();

        assertThat(diff.changed()).singleElement()
                .satisfies(change -> assertThat(change.key()).containsEntry("order_id", 1).containsEntry("line_no", 2));
        assertThat(diff.toString()).contains("~ order_id=1,line_no=2 qty: 1 → 5");
    }

    @Test
    void misuseFailsWithTheReason() {
        assertThatThrownBy(() -> db.table("orders; DROP TABLE orders"))
                .hasMessageContaining("is not a plain table name");
        assertThatThrownBy(() -> db.table("orders").take()).hasMessageContaining("needs keyedBy");
        assertThatThrownBy(() -> db.table("orders").keyedBy("order_id").take())
                .hasMessageContaining("Key column order_id is not a column of orders");
        assertThatThrownBy(() -> db.table("orders").keyedBy("id").maxRows(2).take())
                .hasMessageContaining("more than 2 rows; narrow it with where(...)");
    }
}
