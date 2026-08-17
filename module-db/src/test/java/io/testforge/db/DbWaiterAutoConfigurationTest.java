package io.testforge.db;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.datasource.DataSourceRegistry;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DbWaiterAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TestForgeCoreAutoConfiguration.class,
                    TestForgeDbAutoConfiguration.class));

    @Configuration
    static class SingleDataSourceConfig {
        @Bean
        DataSource singleDs() {
            return DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:autoconf_single;DB_CLOSE_DELAY=-1")
                    .build();
        }
    }

    @Test
    void dbWaiterBeanCreatedWhenNoDataSourcePresent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(DbWaiter.class);
            assertThat(context).doesNotHaveBean(DataSourceRegistry.class);

            DbWaiter waiter = context.getBean(DbWaiter.class);

            // Legacy supplier await works
            String res = waiter.awaitRow("test", () -> Optional.of("ok"));
            assertThat(res).isEqualTo("ok");

            // Datasource methods throw IllegalStateException
            assertThatThrownBy(() -> waiter.on("primary"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No DataSourceRegistry is available");
        });
    }

    @Test
    void dbWaiterBeanInjectedWithRegistryWhenDataSourcePresent() {
        contextRunner.withUserConfiguration(SingleDataSourceConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DbWaiter.class);
            assertThat(context).hasSingleBean(DataSourceRegistry.class);

            DbWaiter waiter = context.getBean(DbWaiter.class);

            // on(null) resolves default singleDs
            assertThat(waiter.on(null)).isNotNull();
        });
    }
}
