package io.testforge.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.artifact.ArtifactSink;
import io.testforge.core.TestForgeCoreAutoConfiguration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** The runner is always there and reads nothing until called. */
@AutoConfiguration(after = TestForgeCoreAutoConfiguration.class,
        afterName = "io.testforge.reporting.TestForgeReportingAutoConfiguration")
@EnableConfigurationProperties(QualityProperties.class)
public class TestForgeQualityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public QualityRunner qualityRunner(QualityProperties properties, ObjectProvider<ObjectMapper> mapper,
                                       ObjectProvider<ArtifactSink> sink) {
        return new QualityRunner(properties, mapper.getIfAvailable(ObjectMapper::new),
                sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
