package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import io.testforge.data.cleanup.Cleanup;
import io.testforge.data.cleanup.CleanupExtension;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

// registrations of the tests below are run after each one, like in any suite
@ExtendWith(CleanupExtension.class)
class TableWriterTest {

    private DriverManagerDataSource dataSource;
    private DbState db;

    @BeforeEach
    void schema() throws SQLException {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        db = DbState.of(dataSource);
        sql("CREATE TABLE customers (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(20))");
        // the foreign key makes the order of deletes matter: the child must go first
        sql("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT REFERENCES customers(id))");
    }

    private void sql(String statement) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement s = connection.createStatement()) {
            s.execute(statement);
        }
    }

    @Test
    void rowsInsertedByAFailedTestAreRemovedChildFirst() {
        TableWriterTestkitFixture.dataSource = dataSource;

        EngineTestKit.engine("junit-jupiter").selectors(selectClass(TableWriterTestkitFixture.class)).execute()
                .testEvents().assertStatistics(stats -> stats.failed(1));

        assertThat(TableWriterTestkitFixture.customerKey).containsOnlyKeys("id");
        assertThat(db.table("orders").keyedBy("id").take().size()).isZero();
        assertThat(db.table("customers").keyedBy("id").take().size()).isZero();
    }

    @Test
    void theKeyComesFromTheValuesOrFromWhatTheDatabaseGenerated() {
        Map<String, Object> generated = db.into("customers").keyedBy("id").withoutCleanup()
                .insert(Map.of("name", "a"));
        Map<String, Object> given = db.into("orders").keyedBy("id").withoutCleanup()
                .insert(Map.of("id", 9, "customer_id", generated.get("id")));

        assertThat(generated.get("id")).isInstanceOf(Integer.class);
        assertThat(given).isEqualTo(Map.of("id", 9));
        assertThat(Cleanup.pending()).isEmpty();
    }

    @Test
    void eachInsertRegistersItsDeleteByName() {
        db.into("customers").keyedBy("id").insert(Map.of("id", 5, "name", "b"));

        assertThat(Cleanup.pending()).first().isEqualTo("customers id=5");
    }

    @Test
    void misuseFailsWithTheReason() {
        assertThatThrownBy(() -> db.into("customers").insert(Map.of("name", "x")))
                .hasMessageContaining("needs keyedBy");
        assertThatThrownBy(() -> db.into("customers").keyedBy("id").insert(Map.of("name; DROP", "x")))
                .hasMessageContaining("is not a plain column name");
        assertThatThrownBy(() -> db.into("orders").keyedBy("customer_id", "id").insert(Map.of("id", 1)))
                .hasMessageContaining("cannot tell its customer_id");
    }
}
