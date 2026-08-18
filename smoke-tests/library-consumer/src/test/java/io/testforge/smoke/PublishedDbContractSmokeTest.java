package io.testforge.smoke;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.db.contract.DbContractReport;
import io.testforge.db.contract.DbContractRunner;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The published module carries SchemaCrawler as a runtime dependency, so a
 * consumer that declares only {@code module-db-contract} can inspect a real
 * schema. If the POM ever stopped carrying it, this test would fail to crawl.
 */
@SpringBootTest(
        classes = SmokeTestApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:db-contract-smoke;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "forge.db-contract.enabled=true",
                "forge.db-contract.schema=PUBLIC",
                "forge.db-contract.include-tables=smoke_.*",
                "forge.db-contract.output-dir=build/db-contract-smoke",
                "forge.db-contract.baseline-file=build/db-contract-smoke/baseline/schema-snapshot.json"
        })
class PublishedDbContractSmokeTest {

    @Autowired
    DbContractRunner dbContractRunner;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void createSchema() throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS smoke_orders");
            statement.execute("CREATE TABLE smoke_orders (id BIGINT PRIMARY KEY, status VARCHAR(32) NOT NULL)");
        }
        dbContractRunner.writeBaseline();
    }

    @Test
    void inspectsAndGatesASchemaFromThePublishedLibrary() throws Exception {
        DbSchemaSnapshot snapshot = dbContractRunner.capture();
        assertThat(snapshot.table("SMOKE_ORDERS")).isPresent();

        assertThat(dbContractRunner.assertCompatible().changes()).isEmpty();

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE smoke_orders DROP COLUMN status");
        }

        DbContractReport report = dbContractRunner.run();

        assertThat(report.compatible()).isFalse();
        assertThat(report.breakingCount()).isEqualTo(1);
        assertThat(Path.of(report.reportMarkdown())).exists();
    }
}
