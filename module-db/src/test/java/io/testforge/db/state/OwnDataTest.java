package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.data.isolation.DataIsolation;
import io.testforge.data.isolation.DataIsolationExtension;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@ExtendWith(DataIsolationExtension.class)
class OwnDataTest {

    private DriverManagerDataSource dataSource;
    private TableSnapshot before;
    private String mine;
    private String parallelTests;

    /** A shared table: this test's customer, a parallel test's, and a real one nobody's test made. */
    @BeforeEach
    void customers() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        execute("CREATE TABLE customers (id BIGINT PRIMARY KEY, name VARCHAR(64), tier VARCHAR(16))");
        mine = DataIsolation.tag("anna");
        parallelTests = DataIsolation.runTag() + "-t99999-boris";
        insert(1, mine);
        insert(2, parallelTests);
        insert(3, "Real Customer Ltd");
        // the whole table, no where: the point is to see what else the action reached
        before = DbState.of(dataSource).table("customers").keyedBy("id").take();
    }

    @Test
    void touchingOnlyOwnRowsPasses() throws Exception {
        execute("UPDATE customers SET tier = 'GOLD' WHERE id = 1");
        insert(4, DataIsolation.tag("carl"));

        before.diff().expect().onlyOwnData("name").onlyThisTestsData("name");
    }

    @Test
    void aParallelTestsRowIsToleratedUnlessStrict() throws Exception {
        execute("UPDATE customers SET tier = 'GOLD' WHERE id IN (1, 2)");

        TableDiff diff = before.diff();
        diff.expect().onlyOwnData("name");
        assertThatThrownBy(() -> diff.expect().onlyThisTestsData("name"))
                .hasMessageContaining("rows outside this test's data were touched")
                .hasMessageContaining("~ id=2 name=" + parallelTests + " (another test's)");
    }

    @Test
    void reachingDataNoTestOwnsFails() throws Exception {
        // the bug: an UPDATE without the tenant filter upgrades every customer
        execute("UPDATE customers SET tier = 'GOLD'", "DELETE FROM customers WHERE id = 3");

        assertThatThrownBy(() -> before.diff().expect().onlyOwnData("name"))
                .isInstanceOf(AssertionError.class)
                .hasMessageStartingWith("rows outside the tests' data were touched (name carries no test tag):")
                .hasMessageContaining("- id=3 name=Real Customer Ltd (not test data)");
    }

    @Test
    void anUnknownTagColumnIsNamed() throws Exception {
        execute("UPDATE customers SET tier = 'GOLD' WHERE id = 1");

        assertThatThrownBy(() -> before.diff().expect().onlyOwnData("email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'email' is not a column of the diff");
    }

    private void insert(long id, String name) throws Exception {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("INSERT INTO customers VALUES (?, ?, 'BASIC')")) {
            statement.setLong(1, id);
            statement.setString(2, name);
            statement.executeUpdate();
        }
    }

    private void execute(String... statements) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }
}
