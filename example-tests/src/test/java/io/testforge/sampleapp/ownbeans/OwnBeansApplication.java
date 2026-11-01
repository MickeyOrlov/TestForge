package io.testforge.sampleapp.ownbeans;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.web.client.RestClient;

/**
 * A service that declares the infrastructure beans a TestForge module could
 * also want: its own {@code ObjectMapper} and {@code RestClient.Builder}.
 * The test checks that the context still holds exactly these.
 */
@SpringBootApplication
public class OwnBeansApplication {

    @Bean
    ObjectMapper applicationObjectMapper(Jackson2ObjectMapperBuilder builder) {
        return builder.build();
    }

    @Bean
    RestClient.Builder applicationRestClientBuilder() {
        return RestClient.builder().defaultHeader("X-Client", "own-beans");
    }
}
