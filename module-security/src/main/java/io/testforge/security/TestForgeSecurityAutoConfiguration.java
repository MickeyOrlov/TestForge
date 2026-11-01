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
      "io.testforge.auth.TestForgeAuthAutoConfiguration",
      "io.testforge.reporting.TestForgeReportingAutoConfiguration"
    })
@EnableConfigurationProperties({SecurityProperties.class, SecurityAuthProperties.class})
public class TestForgeSecurityAutoConfiguration {
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "forge.security.auth", name = "enabled", havingValue = "true")
  public SecurityAuthRunner securityAuthRunner(
      io.testforge.http.ApiClient api,
      io.testforge.auth.TokenProvider tokens,
      io.testforge.auth.AuthProperties auth,
      SecurityAuthProperties properties,
      SecurityCollector collector) {
    return new SecurityAuthRunner(api, tokens, auth, properties, collector);
  }

  @Bean
  @ConditionalOnMissingBean
  public SecurityCollector securityCollector(SecurityProperties properties) {
    return new SecurityCollector(properties.maxEntries());
  }

  @Bean
  @ConditionalOnMissingBean
  public SecurityRunner securityRunner(
      SecurityProperties p,
      SecurityCollector c,
      ObjectProvider<ArtifactSink> sink,
      org.springframework.core.env.Environment environment) {
    return new SecurityRunner(
        p,
        c,
        sink.getIfAvailable(() -> ArtifactSink.NO_OP),
        () -> environment.getProperty("forge.security.auth.enabled", Boolean.class, false));
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnProperty(prefix = "forge.security.passive", name = "enabled", havingValue = "true")
  public PassiveSecurityFilter passiveSecurityFilter(SecurityProperties p, SecurityCollector c) {
    return new PassiveSecurityFilter(new PassiveSecurityChecks(p.passive(), c), c);
  }
}
