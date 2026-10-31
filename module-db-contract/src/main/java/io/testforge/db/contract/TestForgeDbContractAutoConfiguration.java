package io.testforge.db.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.db.TestForgeDbAutoConfiguration;
import io.testforge.db.contract.policy.DbCompatibilityPolicy;
import io.testforge.db.contract.policy.DefaultDbCompatibilityPolicy;
import io.testforge.db.contract.snapshot.DbSchemaInspector;
import io.testforge.db.contract.snapshot.DbSchemaSnapshotStore;
import io.testforge.db.contract.snapshot.SchemaCrawlerDbSchemaInspector;
import io.testforge.db.contract.usage.DbSchemaUsage;
import io.testforge.db.contract.usage.JpaEntityUsage;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.schema.SchemaValidator;
import jakarta.persistence.EntityManagerFactory;
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

    /**
     * Which of the project's JPA entities a change reaches, read lazily from
     * every persistence unit's metamodel; replace the bean to describe usage
     * another way.
     */
    @Bean
    @ConditionalOnMissingBean
    public DbSchemaUsage dbSchemaUsage(
            ObjectProvider<EntityManagerFactory> factories, ObjectProvider<SchemaValidator> validator) {
        return new JpaEntityUsage(() -> factories.orderedStream().toList(), validator::getIfAvailable);
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
            ObjectProvider<ArtifactSink> artifactSink,
            ObjectProvider<DbSchemaUsage> usage) {
        return new DbContractRunner(
                registry,
                inspector,
                snapshotStore,
                policy,
                properties,
                objectMapper.getIfAvailable(ObjectMapper::new),
                artifactSink.getIfAvailable(() -> ArtifactSink.NO_OP),
                usage.getIfAvailable(() -> DbSchemaUsage.NONE));
    }
}
