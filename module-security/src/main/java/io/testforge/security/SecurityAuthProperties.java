package io.testforge.security;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.security.auth")
public record SecurityAuthProperties(
    Boolean enabled,
    List<String> allowedHosts,
    Boolean writeChecks,
    Boolean allowUnsafeMethods,
    Set<Integer> deniedStatuses,
    Integer maxRequests) {
  public SecurityAuthProperties {
    enabled = Boolean.TRUE.equals(enabled);
    writeChecks = Boolean.TRUE.equals(writeChecks);
    allowUnsafeMethods = Boolean.TRUE.equals(allowUnsafeMethods);
    allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
    deniedStatuses = Set.copyOf(deniedStatuses == null ? Set.of(401, 403) : deniedStatuses);
    maxRequests = maxRequests == null ? 1000 : maxRequests;
    if (maxRequests < 1)
      throw new IllegalArgumentException("security.auth.max-requests must be positive");
    if (deniedStatuses.stream().anyMatch(s -> s < 300 || s >= 500 || s == 429))
      throw new IllegalArgumentException(
          "denied-statuses may contain only deliberate 3xx/4xx denials, excluding 429");
  }
}
