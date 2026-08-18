package io.testforge.db.contract;

import static org.assertj.core.api.Assertions.assertThat;

import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.db.TestForgeDbAutoConfiguration;
import io.testforge.db.contract.model.DbSchemaSnapshot;
import io.testforge.db.contract.policy.DbChangeAssessment;
import io.testforge.db.contract.policy.DbCompatibility;
import io.testforge.db.contract.policy.DbCompatibilityPolicy;
import io.testforge.db.contract.policy.DefaultDbCompatibilityPolicy;
import io.testforge.db.contract.snapshot.DbSchemaInspector;
import io.testforge.db.contract.snapshot.DbSchemaSnapshotStore;
import io.testforge.db.contract.snapshot.SchemaCrawlerDbSchemaInspector;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class TestForgeDbContractAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TestForgeCoreAutoConfiguration.class,
                    TestForgeDbAutoConfiguration.class,
                    TestForgeDbContractAutoConfiguration.class));

    @Configuration
    static class DataSourceConfig {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .driverClassName("org.h2.Driver")
                    .url("jdbc:h2:mem:db-contract-autoconf;DB_CLOSE_DELAY=-1")
                    .build();
        }
    }

    @Configuration
    static class CustomPolicyConfig {
        @Bean
        DbCompatibilityPolicy strictPolicy() {
            return (change, baseline, current) ->
                    new DbChangeAssessment(change, DbCompatibility.BREAKING, "this project gates on everything");
        }
    }

    @Test
    void aDataSourceIsAllItTakesToGetARunner() {
        contextRunner.withUserConfiguration(DataSourceConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DbContractRunner.class);
            assertThat(context).hasSingleBean(DbSchemaInspector.class);
            assertThat(context).hasSingleBean(DbSchemaSnapshotStore.class);
            assertThat(context.getBean(DbSchemaInspector.class))
                    .isInstanceOf(SchemaCrawlerDbSchemaInspector.class);
            assertThat(context.getBean(DbCompatibilityPolicy.class))
                    .isInstanceOf(DefaultDbCompatibilityPolicy.class);
        });
    }

    @Test
    void withoutADataSourceThereIsNoRunner_butTheRestOfTheModuleStillWires() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(DbContractRunner.class);
            assertThat(context).hasSingleBean(DbSchemaInspector.class);
        });
    }

    @Test
    void theCheckIsDisabledUntilAProjectAsksForIt() {
        contextRunner.withUserConfiguration(DataSourceConfig.class).run(context -> {
            DbContractProperties properties = context.getBean(DbContractProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.schema()).isEqualTo("public");
            assertThat(properties.failOn().breaking()).isTrue();
            assertThat(properties.failOn().risky()).isFalse();
            assertThat(properties.failOn().unknown()).isFalse();
        });
    }

    @Test
    void propertiesBindFromTheForgeDbContractPrefix() {
        contextRunner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues(
                        "forge.db-contract.enabled=true",
                        "forge.db-contract.schema=inventory",
                        "forge.db-contract.exclude-tables=flyway_.*",
                        "forge.db-contract.fail-on.risky=true")
                .run(context -> {
                    DbContractProperties properties = context.getBean(DbContractProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.schema()).isEqualTo("inventory");
                    assertThat(properties.excludeTables()).isEqualTo("flyway_.*");
                    assertThat(properties.failOn().risky()).isTrue();
                });
    }

    @Test
    void aProjectCanReplaceTheCompatibilityPolicy() {
        contextRunner
                .withUserConfiguration(DataSourceConfig.class, CustomPolicyConfig.class)
                .run(context -> assertThat(context.getBean(DbCompatibilityPolicy.class))
                        .isNotInstanceOf(DefaultDbCompatibilityPolicy.class));
    }

    @Test
    void theRunnerReadsTheRealSchemaThroughTheDefaultDataSource() {
        contextRunner.withUserConfiguration(DataSourceConfig.class)
                .withPropertyValues("forge.db-contract.enabled=true", "forge.db-contract.schema=PUBLIC")
                .run(context -> {
                    DbSchemaSnapshot snapshot = context.getBean(DbContractRunner.class).capture();

                    assertThat(snapshot.schema()).isEqualTo("PUBLIC");
                });
    }
}
