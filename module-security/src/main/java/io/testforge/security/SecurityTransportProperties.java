package io.testforge.security;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "forge.security.transport")
public record SecurityTransportProperties(
    Boolean enabled,
    List<String> allowedHosts,
    Integer expiryWarningDays,
    Duration timeout,
    Boolean probeLegacy,
    Boolean probeWeakCiphers) {
  public SecurityTransportProperties {
    enabled = Boolean.TRUE.equals(enabled);
    allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
    expiryWarningDays = expiryWarningDays == null ? 30 : expiryWarningDays;
    timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    probeLegacy = probeLegacy == null ? true : probeLegacy;
    probeWeakCiphers = probeWeakCiphers == null ? true : probeWeakCiphers;
    if (expiryWarningDays < 0
        || timeout.isNegative()
        || timeout.toMillis() < 1
        || timeout.compareTo(Duration.ofMinutes(1)) > 0)
      throw new IllegalArgumentException("invalid security.transport expiry window or timeout");
  }
}
