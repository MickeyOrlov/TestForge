package io.testforge.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import io.testforge.core.wait.Waiter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;

@AutoConfiguration(
        after = TestForgeCoreAutoConfiguration.class,
        afterName = "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration")
@EnableConfigurationProperties(KafkaProbeProperties.class)
public class TestForgeKafkaAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public KafkaMessageBuffer kafkaMessageBuffer(KafkaProbeProperties properties) {
        return new KafkaMessageBuffer(properties.maxMessagesPerTopic());
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaProbe kafkaProbe(
            KafkaMessageBuffer buffer,
            Waiter waiter,
            ObjectMapper objectMapper) {
        return new KafkaProbe(buffer, waiter, objectMapper);
    }

    @Bean
    @ConditionalOnBean(ConsumerFactory.class)
    @ConditionalOnProperty(prefix = "forge.kafka", name = "enabled", havingValue = "true")
    public KafkaPollingCollector kafkaPollingCollector(
            ConsumerFactory<String, String> consumerFactory,
            KafkaProbeProperties properties,
            KafkaMessageBuffer buffer) {
        return new KafkaPollingCollector(consumerFactory, properties, buffer);
    }
}
