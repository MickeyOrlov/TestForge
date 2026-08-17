package io.testforge.db;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.Waiter;
import io.testforge.db.datasource.DataSourceRegistry;
import io.testforge.db.logging.SqlLoggingDataSourcePostProcessor;
import io.testforge.db.repository.RepositoryPollingAspect;
import io.testforge.db.repository.RepositoryPollingProperties;
import io.testforge.db.schema.SchemaValidator;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
    public DbWaiter dbWaiter(Waiter waiter) {
        return new DbWaiter(waiter);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DataSource.class)
    public SchemaValidator schemaValidator(DataSourceRegistry registry) {
        return new SchemaValidator(registry);
    }

    @Bean
    @ConditionalOnProperty(prefix = "forge.db", name = "log-sql", havingValue = "true")
    public static SqlLoggingDataSourcePostProcessor sqlLoggingDataSourcePostProcessor() {
        return new SqlLoggingDataSourcePostProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.data.repository.Repository")
    @ConditionalOnProperty(prefix = "forge.db.repository-polling", name = "enabled", havingValue = "true")
    public RepositoryPollingAspect repositoryPollingAspect(DbWaiter dbWaiter) {
        return new RepositoryPollingAspect(dbWaiter);
    }
}
