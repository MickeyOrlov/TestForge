package io.testforge.example;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.testforge.data.isolation.DataIsolation;
import io.testforge.data.isolation.DataIsolationExtension;
import io.testforge.db.state.DbState;
import io.testforge.db.state.TableSnapshot;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * On a shared environment a test must change its own data only. The table is
 * watched whole; every row the action touched must carry a test tag in the
 * column the test writes it into (here the customer's name).
 */
@SpringBootTest
@ExtendWith(DataIsolationExtension.class)
class OwnDataExampleTest {

    @Autowired
    DbState db;

    @Autowired
    JdbcTemplate jdbc;

    private long id;

    @BeforeEach
    void customers() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS own_customers (id BIGINT PRIMARY KEY, name VARCHAR(96), tier VARCHAR(16))");
        id = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE / 2);
        jdbc.update("INSERT INTO own_customers VALUES (?, ?, 'BASIC')", id, DataIsolation.tag("anna"));
        // somebody's real customer on the same environment
        jdbc.update("INSERT INTO own_customers VALUES (?, 'Real Customer Ltd', 'BASIC')", id + 1);
    }

    private TableSnapshot allCustomers() {
        return db.table("own_customers").keyedBy("id").maxRows(100_000).take();
    }

    @Test
    void upgradingTheTestsCustomerTouchesTheTestsDataOnly() {
        TableSnapshot before = allCustomers();

        jdbc.update("UPDATE own_customers SET tier = 'GOLD' WHERE id = ?", id);

        before.diff().expect().onlyOwnData("name");
    }

    @Test
    void anUpdateThatReachesRealDataIsCaught() {
        TableSnapshot before = allCustomers();

        // the bug: the tenant filter is missing, so every customer is upgraded
        jdbc.update("UPDATE own_customers SET tier = 'GOLD' WHERE id IN (?, ?)", id, id + 1);

        assertThatThrownBy(() -> before.diff().expect().onlyOwnData("name"))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("name=Real Customer Ltd (not test data)");
    }
}
