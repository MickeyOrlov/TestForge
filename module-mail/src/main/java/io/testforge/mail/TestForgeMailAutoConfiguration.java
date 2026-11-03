package io.testforge.mail;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(MailProperties.class)
@ConditionalOnProperty(prefix="forge.mail", name="enabled", havingValue="true")
public class TestForgeMailAutoConfiguration {
    @Bean @ConditionalOnMissingBean public MailBackend mailBackend(MailProperties properties) { return new MailpitBackend(properties); }
    @Bean @ConditionalOnMissingBean public Mailpit mailpit(MailProperties properties, MailBackend backend, ObjectProvider<ArtifactSink> sink) {
        return new Mailpit(properties, backend, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
    }
}
