package io.testforge.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.Waiter;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
        after = TestForgeCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration")
@EnableConfigurationProperties(RabbitProbeProperties.class)
public class TestForgeRabbitAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RabbitMessageBuffer rabbitMessageBuffer(RabbitProbeProperties properties) {
        return new RabbitMessageBuffer(properties.maxMessagesPerExchange());
    }

    @Bean
    @ConditionalOnMissingBean
    public RabbitProbe rabbitProbe(RabbitMessageBuffer buffer, Waiter waiter, ObjectMapper objectMapper) {
        return new RabbitProbe(buffer, waiter, objectMapper);
    }

    /** Present wherever Spring Boot made a ConnectionFactory; connects only when a test publishes. */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConnectionFactory.class)
    public RabbitPublisher rabbitPublisher(ConnectionFactory connectionFactory, ObjectMapper objectMapper,
                                           RabbitProbeProperties properties) {
        return new RabbitPublisher(connectionFactory, objectMapper, properties.publish());
    }

    @Bean
    @ConditionalOnBean(ConnectionFactory.class)
    @ConditionalOnProperty(prefix = "forge.rabbitmq", name = "enabled", havingValue = "true")
    public RabbitCollector rabbitCollector(ConnectionFactory connectionFactory, RabbitProbeProperties properties,
                                           RabbitMessageBuffer buffer) {
        return new RabbitCollector(connectionFactory, properties, buffer);
    }
}
