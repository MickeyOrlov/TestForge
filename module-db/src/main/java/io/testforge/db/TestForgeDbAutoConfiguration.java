package io.testforge.db;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.Waiter;
import io.testforge.db.logging.SqlLoggingDataSourcePostProcessor;
import io.testforge.db.repository.RepositoryWaiterAspect;
import io.testforge.db.repository.RepositoryWaiterProperties;
import io.testforge.db.schema.SchemaValidator;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

// DataSourceAutoConfiguration referenced by name: it lives in the optional
// spring-boot-jdbc module (Boot 4 modularization) and may be absent here
@AutoConfiguration(
        after = TestForgeCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@EnableConfigurationProperties(RepositoryWaiterProperties.class)
public class TestForgeDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DbWaiter dbWaiter(Waiter waiter) {
        return new DbWaiter(waiter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public SchemaValidator schemaValidator(DataSource dataSource) {
        return new SchemaValidator(dataSource);
    }

    @Bean
    @ConditionalOnProperty(prefix = "forge.db", name = "log-sql", havingValue = "true")
    public static SqlLoggingDataSourcePostProcessor sqlLoggingDataSourcePostProcessor() {
        return new SqlLoggingDataSourcePostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    @ConditionalOnProperty(prefix = "forge.db.repository-waiter", name = "enabled", havingValue = "true")
    public RepositoryWaiterAspect repositoryWaiterAspect(DbWaiter dbWaiter) {
        return new RepositoryWaiterAspect(dbWaiter);
    }
}
