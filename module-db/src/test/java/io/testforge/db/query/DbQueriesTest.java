package io.testforge.db.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DbQueriesTest {

    private final QueryRecorder recorder = new QueryRecorder();
    private final DbQueries queries = new DbQueries(recorder);
    private final ExecutorService server = Executors.newSingleThreadExecutor();
    private DataSource dataSource;

    @BeforeEach
    void shop() throws Exception {
        DataSource h2 = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource = ProxyDataSourceBuilder.create(h2).name("shop")
                .listener(new QueryRecorderListener(() -> recorder)).build();
        execute("CREATE TABLE orders (id BIGINT PRIMARY KEY, customer_id BIGINT)",
                "CREATE TABLE items (id BIGINT PRIMARY KEY, order_id BIGINT, sku VARCHAR(8))");
        for (int order = 1; order <= 5; order++) {
            execute("INSERT INTO orders VALUES (" + order + ", 7)",
                    "INSERT INTO items VALUES (" + (order * 10) + ", " + order + ", 'A')");
        }
    }

    @AfterEach
    void stop() {
        server.shutdownNow();
    }

    /** The N+1 a lazy collection produces: one query for the orders, one per order for its items. */
    private void listOrdersLazily() {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement orders = connection.prepareStatement("SELECT id FROM orders WHERE customer_id = ?")) {
                orders.setLong(1, 7);
                orders.executeQuery().close();
            }
            for (long order = 1; order <= 5; order++) {
                try (PreparedStatement items = connection.prepareStatement("SELECT sku FROM items WHERE order_id = ?")) {
                    items.setLong(1, order);
                    items.executeQuery().close();
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void theNPlusOneIsOneShapeRunOncePerRow() {
        CapturedQueries sql = queries.during(this::listOrdersLazily);

        assertThat(sql.count()).isEqualTo(6);
        assertThat(sql.count(CapturedQueries.Kind.SELECT)).isEqualTo(6);
        assertThat(sql.shapes()).containsEntry("SELECT sku FROM items WHERE order_id = ?", 5);
        sql.expect().atMost(10).none(CapturedQueries.Kind.DELETE);
        assertThatThrownBy(() -> sql.expect().noRepeated(2))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("statements repeated more than 2 times (N+1?): "
                        + "[5 × SELECT sku FROM items WHERE order_id = ?]")
                .hasMessageContaining("6 statements (SELECT 6)");
        assertThatThrownBy(() -> sql.expect().atMost(3))
                .hasMessageStartingWith("expected at most 3 statements, ran 6");
    }

    @Test
    void literalsAndInListsAreFoldedSoRepeatsGroup() {
        CapturedQueries sql = queries.during(() -> {
            execute("SELECT * FROM items WHERE order_id = 1 AND sku = 'A'",
                    "SELECT * FROM items WHERE order_id = 2 AND sku = 'B'",
                    "DELETE FROM items WHERE id IN (10, 20, 30)",
                    "DELETE FROM items WHERE id IN (40)");
        });

        assertThat(sql.shapes())
                .containsEntry("SELECT * FROM items WHERE order_id = ? AND sku = ?", 2)
                .containsEntry("DELETE FROM items WHERE id IN (?)", 2);
        assertThatThrownBy(() -> sql.expect().none(CapturedQueries.Kind.DELETE))
                .hasMessageStartingWith("expected no DELETE statements, ran 2");
    }

    @Test
    void aBatchCountsEveryRow() throws Exception {
        CapturedQueries sql = queries.during(() -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO items VALUES (?, 1, 'B')")) {
                for (int i = 100; i < 150; i++) {
                    insert.setLong(1, i);
                    insert.addBatch();
                }
                insert.executeBatch();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        assertThat(sql.count(CapturedQueries.Kind.INSERT)).isEqualTo(50);
        assertThatThrownBy(() -> sql.expect().atMost(10, CapturedQueries.Kind.INSERT))
                .hasMessageStartingWith("expected at most 10 INSERT statements, ran 50");
    }

    @Test
    void anApplicationThreadCountsUnlessTheWindowIsThisThreadOnly() {
        Runnable request = () -> {
            try {
                // the application under test answers on its own thread
                server.submit(this::listOrdersLazily).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };

        assertThat(queries.during(request).count()).isEqualTo(6);
        assertThat(queries.duringThisThread(request).count()).isZero();
        assertThat(queries.duringThisThread(this::listOrdersLazily).count()).isEqualTo(6);
    }

    @Test
    void statementsOutsideTheWindowDoNotCount() {
        DbQueries.Result<Integer> result = queries.returning(() -> {
            execute("SELECT 1");
            return 42;
        });
        execute("SELECT 2");

        assertThat(result.value()).isEqualTo(42);
        assertThat(result.queries().count()).isEqualTo(1);
    }

    private void execute(String... statements) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
