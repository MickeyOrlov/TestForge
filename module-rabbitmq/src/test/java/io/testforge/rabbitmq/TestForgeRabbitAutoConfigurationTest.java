package io.testforge.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Nothing connects at startup unless the collector is asked for. */
class TestForgeRabbitAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class, RabbitAutoConfiguration.class,
                    TestForgeRabbitAutoConfiguration.class));

    @Test
    void probeAndPublisherAreThereWithoutABrokerAndTheCollectorIsOff() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(RabbitProbe.class).hasSingleBean(RabbitPublisher.class)
                    .doesNotHaveBean(RabbitCollector.class);
        });
    }

    @Test
    void theCollectorIsCreatedOnlyWhenEnabled() {
        runner.withPropertyValues("forge.rabbitmq.enabled=true")
                // no bindings: the collector exists but does not connect
                .run(context -> assertThat(context).hasSingleBean(RabbitCollector.class));
    }

    @Test
    void withoutSpringRabbitsConnectionFactoryThereIsNoPublisher() {
        new ApplicationContextRunner()
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withConfiguration(AutoConfigurations.of(TestForgeCoreAutoConfiguration.class,
                        TestForgeRabbitAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(RabbitProbe.class)
                        .doesNotHaveBean(RabbitPublisher.class));
    }
}
