package io.testforge.db.datasource;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.TestForgeDbAutoConfiguration;
import io.testforge.db.schema.SchemaValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceRegistryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TestForgeCoreAutoConfiguration.class,
                    TestForgeDbAutoConfiguration.class));

    private static DataSource createDataSource(String url) {
        return DataSourceBuilder.create()
                .driverClassName("org.h2.Driver")
                .url(url)
                .build();
    }

    @Configuration
    static class SingleDataSourceConfig {
        @Bean
        DataSource singleDs() {
            return createDataSource("jdbc:h2:mem:single;DB_CLOSE_DELAY=-1");
        }
    }

    @Configuration
    static class TwoDataSourcesWithPrimaryConfig {
        @Bean
        @Primary
        DataSource primaryDs() {
            return createDataSource("jdbc:h2:mem:primary;DB_CLOSE_DELAY=-1");
        }

        @Bean
        DataSource auditDs() {
            return createDataSource("jdbc:h2:mem:audit;DB_CLOSE_DELAY=-1");
        }
    }

    @Configuration
    static class TwoDataSourcesNoPrimaryConfig {
        @Bean
        DataSource dsOne() {
            return createDataSource("jdbc:h2:mem:ds1;DB_CLOSE_DELAY=-1");
        }

        @Bean
        DataSource dsTwo() {
            return createDataSource("jdbc:h2:mem:ds2;DB_CLOSE_DELAY=-1");
        }

        // Provide a dummy SchemaValidator bean to bypass auto-configuration's SchemaValidator bean method,
        // which still requires a single DataSource in this task before later tasks update it.
        @Bean
        SchemaValidator schemaValidator() {
            return new SchemaValidator(dsOne());
        }
    }

    @Configuration
    static class TwoPrimaryDataSourcesConfig {
        @Bean
        @Primary
        DataSource dsA() {
            return createDataSource("jdbc:h2:mem:dsa;DB_CLOSE_DELAY=-1");
        }

        @Bean
        @Primary
        DataSource dsB() {
            return createDataSource("jdbc:h2:mem:dsb;DB_CLOSE_DELAY=-1");
        }

        @Bean
        SchemaValidator schemaValidator() {
            return new SchemaValidator(dsA());
        }
    }

    @Test
    void withOneDataSourceBeanResolvesThatBeanAsDefault() {
        contextRunner
                .withUserConfiguration(SingleDataSourceConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRegistry.class);
                    DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                    assertThat(registry.defaultName()).isEqualTo("singleDs");
                    assertThat(registry.resolveDefault()).isSameAs(context.getBean("singleDs", DataSource.class));
                });
    }

    @Test
    void withTwoDataSourcesWhereOneIsPrimaryPrimaryWins() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesWithPrimaryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRegistry.class);
                    DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                    assertThat(registry.defaultName()).isEqualTo("primaryDs");
                    assertThat(registry.resolveDefault()).isSameAs(context.getBean("primaryDs", DataSource.class));
                    assertThat(registry.resolve("auditDs")).isSameAs(context.getBean("auditDs", DataSource.class));
                });
    }

    @Test
    void withTwoDataSourcesNeitherPrimaryDefaultDatasourcePropertyPicksWinner() {
        contextRunner
                .withUserConfiguration(TwoDataSourcesNoPrimaryConfig.class)
                .withPropertyValues("forge.db.default-datasource=dsTwo")
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRegistry.class);
                    DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                    assertThat(registry.defaultName()).isEqualTo("dsTwo");
                    assertThat(registry.resolveDefault()).isSameAs(context.getBean("dsTwo", DataSource.class));
                });
    }

    @Test
    void withZeroDataSourceBeansNoRegistryBeanCreated() {
        contextRunner
                .run(context -> assertThat(context).doesNotHaveBean(DataSourceRegistry.class));
    }

    @Test
    void withTwoPrimaryDataSourcesTreatedAsNoDefault() {
        contextRunner
                .withUserConfiguration(TwoPrimaryDataSourcesConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSourceRegistry.class);
                    DataSourceRegistry registry = context.getBean(DataSourceRegistry.class);
                    assertThatThrownBy(registry::defaultName)
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Multiple DataSources configured");
                });
    }
}

