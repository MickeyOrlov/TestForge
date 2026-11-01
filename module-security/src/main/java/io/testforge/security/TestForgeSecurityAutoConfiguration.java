package io.testforge.security;

import io.testforge.artifact.ArtifactSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(
    afterName = {
      "io.testforge.http.TestForgeHttpAutoConfiguration",
      "io.testforge.reporting.TestForgeReportingAutoConfiguration"
    })
@EnableConfigurationProperties(SecurityProperties.class)
public class TestForgeSecurityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  public SecurityCollector securityCollector(SecurityProperties properties) {
    return new SecurityCollector(properties.maxEntries());
  }

  @Bean
  @ConditionalOnMissingBean
  public SecurityRunner securityRunner(
      SecurityProperties p, SecurityCollector c, ObjectProvider<ArtifactSink> sink) {
    return new SecurityRunner(p, c, sink.getIfAvailable(() -> ArtifactSink.NO_OP));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "forge.security.passive", name = "enabled", havingValue = "true")
  public PassiveSecurityFilter passiveSecurityFilter(SecurityProperties p, SecurityCollector c) {
    return new PassiveSecurityFilter(new PassiveSecurityChecks(p.passive(), c), c);
  }
}
