package io.testforge.db.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.db.TestForgeDbAutoConfiguration;
import io.testforge.db.contract.policy.DbCompatibilityPolicy;
import io.testforge.db.contract.policy.DefaultDbCompatibilityPolicy;
import io.testforge.db.contract.snapshot.DbSchemaInspector;
import io.testforge.db.contract.snapshot.DbSchemaSnapshotStore;
import io.testforge.db.contract.snapshot.SchemaCrawlerDbSchemaInspector;
import io.testforge.db.datasource.DataSourceRegistry;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Wires the database contract check. Beans are created whenever a
 * {@link DataSource} is present; nothing connects to a database until a test
 * calls {@link DbContractRunner}, and the check itself stays inert until
 * {@code forge.db-contract.enabled=true}.
 */
@AutoConfiguration(after = TestForgeDbAutoConfiguration.class)
@EnableConfigurationProperties(DbContractProperties.class)
public class TestForgeDbContractAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DbSchemaInspector dbSchemaInspector(DbContractProperties properties) {
        return new SchemaCrawlerDbSchemaInspector(properties.includeTables(), properties.excludeTables());
    }

    @Bean
    @ConditionalOnMissingBean
    public DbSchemaSnapshotStore dbSchemaSnapshotStore(ObjectProvider<ObjectMapper> objectMapper) {
        return new DbSchemaSnapshotStore(objectMapper.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnMissingBean
    public DbCompatibilityPolicy dbCompatibilityPolicy() {
        return new DefaultDbCompatibilityPolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public DbContractRunner dbContractRunner(
            DataSourceRegistry registry,
            DbSchemaInspector inspector,
            DbSchemaSnapshotStore snapshotStore,
            DbCompatibilityPolicy policy,
            DbContractProperties properties,
            ObjectProvider<ObjectMapper> objectMapper,
            ObjectProvider<ArtifactSink> artifactSink) {
        return new DbContractRunner(
                registry,
                inspector,
                snapshotStore,
                policy,
                properties,
                objectMapper.getIfAvailable(ObjectMapper::new),
                artifactSink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
