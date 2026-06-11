package io.testforge.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.ContractMappers;
import io.testforge.contract.json.JsonContractValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(ContractProperties.class)
public class TestForgeContractAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonContractValidator jsonContractValidator(ContractProperties properties) {
        // deliberately NOT the context mapper: a validator must not inherit
        // lenient application-level parsing customizations
        return new JsonContractValidator(ContractMappers.strict(), properties);
    }
}
