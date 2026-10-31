package io.testforge.db.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.artifact.ArtifactSink;
import io.testforge.artifact.TestArtifact;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.TestForgeDbAutoConfiguration;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** Run only by {@link DbDiagnosticsExtensionTest} through EngineTestKit; one test fails on purpose. */
@SpringJUnitConfig(DbDiagnosticsTestkitFixture.Config.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:db-diagnostics;DB_CLOSE_DELAY=-1",
        "forge.db.diagnostics.enabled=true",
        "forge.db.diagnostics.last=3"
})
@ExtendWith(DbDiagnosticsExtension.class)
class DbDiagnosticsTestkitFixture {

    static final List<String> WRITTEN = new CopyOnWriteArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, JdbcTemplateAutoConfiguration.class,
            TestForgeCoreAutoConfiguration.class, TestForgeDbAutoConfiguration.class})
    static class Config {

        @Bean
        ArtifactSink recordingSink() {
            return new ArtifactSink() {
                @Override
                public Path directoryFor(String source) {
                    return Path.of("unused");
                }

                @Override
                public void register(TestArtifact artifact) {
                }

                @Override
                public TestArtifact write(String source, String category, String name, String mediaType,
                                          String content) {
                    WRITTEN.add(source + "/" + name + "\n" + content);
                    return ArtifactSink.NO_OP.write(source, category, name, mediaType, content);
                }
            };
        }
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void failsAfterItsQueries() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY, status VARCHAR(20))");
        jdbc.update("DELETE FROM orders");
        jdbc.update("INSERT INTO orders VALUES (?, ?)", 1, "NEW");
        jdbc.batchUpdate("INSERT INTO orders VALUES (?, ?)", List.of(new Object[] {2, "NEW"}, new Object[] {3, "PAID"}));
        String status = jdbc.queryForObject("SELECT status FROM orders WHERE id = ?", String.class, 1);
        assertThat(status).as("the order status").isEqualTo("PAID");
    }

    @Test
    void passes() {
        jdbc.queryForObject("SELECT 1", Integer.class);
    }
}
