package io.testforge.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.contract.json.ContractMappers;
import io.testforge.contract.json.JsonContractValidator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

// after Spring Boot's Jackson: the mapper below is a fallback for a context
// without it, never a replacement for the application's own mapper, which a
// @SpringBootTest's service under test serves its requests with
@AutoConfiguration(afterName = "org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration")
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
