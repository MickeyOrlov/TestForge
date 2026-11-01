package io.testforge.db;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.Waiter;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.diagnostics.SqlJournal;
import io.testforge.db.logging.SqlLoggingDataSourcePostProcessor;
import io.testforge.db.repository.RepositoryPollingAspect;
import io.testforge.db.repository.RepositoryPollingProperties;
import io.testforge.db.schema.SchemaNamingStrategy;
import io.testforge.db.schema.SchemaValidator;
import io.testforge.db.state.DbState;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@AutoConfiguration(
        after = TestForgeCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration")
@EnableConfigurationProperties({DbProperties.class, RepositoryPollingProperties.class})
public class TestForgeDbAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public DataSourceRegistry dataSourceRegistry(ConfigurableListableBeanFactory beanFactory, DbProperties properties) {
        Map<String, DataSource> dataSources = beanFactory.getBeansOfType(DataSource.class);
        String defaultName = null;
        if (properties != null && properties.defaultDatasource() != null && !properties.defaultDatasource().isBlank()) {
            defaultName = properties.defaultDatasource();
        } else {
            String primaryName = null;
            int primaryCount = 0;
            for (String name : dataSources.keySet()) {
                if (beanFactory.containsBeanDefinition(name)) {
                    BeanDefinition bd = beanFactory.getBeanDefinition(name);
                    if (bd.isPrimary()) {
                        primaryCount++;
                        primaryName = name;
                    }
                }
            }
            if (primaryCount == 1) {
                defaultName = primaryName;
            } else if (primaryCount == 0 && dataSources.size() == 1) {
                defaultName = dataSources.keySet().iterator().next();
            }
        }
        return new DataSourceRegistry(dataSources, defaultName);
    }

    @Bean
    @ConditionalOnMissingBean
    public DbWaiter dbWaiter(Waiter waiter, ObjectProvider<DataSourceRegistry> registry) {
        return new DbWaiter(waiter, registry.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public SchemaValidator schemaValidator(DataSourceRegistry registry, DbProperties properties,
                                           ObjectProvider<SchemaNamingStrategy> naming) {
        return new SchemaValidator(registry)
                .withNaming(naming.getIfAvailable(() -> SchemaNamingStrategy.named(properties.schemaNaming())))
                .withImplicitForeignKeys(properties.implicitForeignKeys());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public DbState dbState(DataSourceRegistry registry) {
        return new DbState(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.db.diagnostics", name = "enabled", havingValue = "true")
    public SqlJournal sqlJournal(DbProperties properties) {
        return new SqlJournal(properties.diagnostics().last());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.db.queries", name = "enabled", havingValue = "true")
    public io.testforge.db.query.QueryRecorder queryRecorder() {
        return new io.testforge.db.query.QueryRecorder();
    }

    /** Run-wide statement shapes for module-quality's db-queries dimension. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.db.queries", name = "enabled", havingValue = "true")
    public io.testforge.db.query.DbQuerySummary dbQuerySummary(io.testforge.db.query.QueryRecorder recorder,
            ObjectProvider<io.testforge.artifact.ArtifactSink> sink, Environment environment) {
        return new io.testforge.db.query.DbQuerySummary(recorder,
                sink.getIfAvailable(() -> io.testforge.artifact.ArtifactSink.NO_OP),
                environment.getProperty("forge.db.queries.slow-millis", Long.class, 1000L));
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "forge.db.queries", name = "enabled", havingValue = "true")
    public io.testforge.db.query.DbQueries dbQueries(io.testforge.db.query.QueryRecorder recorder) {
        return new io.testforge.db.query.DbQueries(recorder);
    }

    /** One proxy for all: logging ({@code log-sql}), diagnostics, and query checks. */
    @Bean
    @ConditionalOnExpression("${forge.db.log-sql:false} or ${forge.db.diagnostics.enabled:false} "
            + "or ${forge.db.queries.enabled:false}")
    public static SqlLoggingDataSourcePostProcessor sqlLoggingDataSourcePostProcessor(
            Environment environment, ObjectProvider<SqlJournal> journal,
            ObjectProvider<io.testforge.db.query.QueryRecorder> recorder) {
        boolean log = environment.getProperty("forge.db.log-sql", Boolean.class, false);
        boolean record = environment.getProperty("forge.db.diagnostics.enabled", Boolean.class, false);
        boolean queries = environment.getProperty("forge.db.queries.enabled", Boolean.class, false);
        return new SqlLoggingDataSourcePostProcessor(log, record ? journal::getIfAvailable : null,
                queries ? recorder::getIfAvailable : null);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    @ConditionalOnProperty(prefix = "forge.db.repository-polling", name = "enabled", havingValue = "true")
    public RepositoryPollingAspect repositoryPollingAspect(DbWaiter dbWaiter) {
        return new RepositoryPollingAspect(dbWaiter);
    }
}
