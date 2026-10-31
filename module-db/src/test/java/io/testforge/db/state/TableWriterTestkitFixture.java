package io.testforge.db.state;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.data.cleanup.CleanupExtension;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Run only by {@link TableWriterTest} through EngineTestKit; one test fails on purpose. */
@ExtendWith(CleanupExtension.class)
class TableWriterTestkitFixture {

    static volatile DriverManagerDataSource dataSource;
    static volatile Map<String, Object> customerKey;

    @Test
    void insertsAParentAndAChildThenFails() {
        DbState db = DbState.of(dataSource);
        customerKey = db.into("customers").keyedBy("id").insert(Map.of("name", "c-1"));
        db.into("orders").keyedBy("id").insert(Map.of("id", 7, "customer_id", customerKey.get("id")));

        assertThat(db.table("orders").keyedBy("id").take().size()).isEqualTo(1);
        assertThat(1).as("the order total").isEqualTo(2);
    }
}
