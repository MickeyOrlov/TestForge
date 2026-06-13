package io.testforge.contract.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.JsonContractValidator;
import io.testforge.kafka.KafkaProbe;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = {
        "io.testforge.kafka.TestForgeKafkaAutoConfiguration",
        "io.testforge.contract.TestForgeContractAutoConfiguration"
})
@EnableConfigurationProperties(ContractMonitorProperties.class)
public class TestForgeContractMonitorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PayloadShapeNormalizer payloadShapeNormalizer() {
        return new PayloadShapeNormalizer();
    }

    @Bean
    @ConditionalOnMissingBean
    public ContractMonitorRunner contractMonitorRunner(
            ObjectProvider<ContractMonitorCase> cases,
            KafkaProbe kafkaProbe,
            JsonContractValidator validator,
            PayloadShapeNormalizer normalizer,
            ObjectMapper objectMapper,
            ContractMonitorProperties properties) {
        List<ContractMonitorCase> orderedCases = cases.orderedStream().toList();
        return new ContractMonitorRunner(orderedCases, kafkaProbe, validator, normalizer, objectMapper, properties);
    }
}
