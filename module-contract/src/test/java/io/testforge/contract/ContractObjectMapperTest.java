package io.testforge.contract;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The module's own mapper is a fallback for a context without Spring Boot's
 * Jackson auto-configuration, never a replacement for it: a @SpringBootTest
 * runs the service under test in the same context, and its MVC must keep the
 * application's mapper (spring.jackson.*, unknown properties ignored).
 */
class ContractObjectMapperTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class, TestForgeContractAutoConfiguration.class));

    @Test
    void springBootsMapperStaysTheApplicationsMapper() {
        runner.withPropertyValues("spring.jackson.deserialization.fail-on-unknown-properties=false")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectMapper.class);
                    assertThat(context.getBean(ObjectMapper.class)
                            .isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
                });
    }

    @Test
    void withoutJacksonAutoConfigurationTheModuleStillHasAMapper() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TestForgeContractAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(ObjectMapper.class));
    }
}
