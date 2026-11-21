package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

@ConfigurationProperties(prefix = "forge.security.transport")
public record SecurityTransportProperties(
        Boolean enabled,
        List<String> allowedHosts,
        Integer expiryWarningDays,
        Duration timeout,
        Boolean probeLegacy,
        Boolean probeWeakCiphers,
        Testssl testssl) {
    @ConstructorBinding
    public SecurityTransportProperties {
        enabled = Boolean.TRUE.equals(enabled);
        allowedHosts = List.copyOf(allowedHosts == null ? List.of() : allowedHosts);
        expiryWarningDays = expiryWarningDays == null ? 30 : expiryWarningDays;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
        // off by default: Java 21 disables legacy TLS and weak ciphers, so enabled probes are
        // not_measured there and the gate could never pass; enable on a JDK that still offers them
        probeLegacy = probeLegacy == null ? false : probeLegacy;
        probeWeakCiphers = probeWeakCiphers == null ? false : probeWeakCiphers;
        testssl = testssl == null ? new Testssl(null, null, null, null, null) : testssl;
        if (expiryWarningDays < 0
                || timeout.isNegative()
                || timeout.toMillis() < 1
                || timeout.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException(
                    "invalid security.transport expiry window or timeout");
        var hosts =
                allowedHosts.stream()
                        .map(h -> h.replaceFirst(":\\d+$", "").toLowerCase(Locale.ROOT))
                        .toList();
        for (String alias : testssl.hostAliases())
            if (!hosts.contains(alias.toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException(
                        "security.transport.testssl.host-aliases must be allowed hosts");
    }

    /** The JDK checks alone, without the delegated testssl.sh assessment. */
    public SecurityTransportProperties(
            Boolean enabled,
            List<String> allowedHosts,
            Integer expiryWarningDays,
            Duration timeout,
            Boolean probeLegacy,
            Boolean probeWeakCiphers) {
        this(enabled, allowedHosts, expiryWarningDays, timeout, probeLegacy, probeWeakCiphers, null);
    }

    /**
     * Delegated TLS assessment with testssl.sh in a pinned container: protocols including SSLv2/3
     * and TLS 1.0/1.1, cipher categories, forward secrecy, server defaults (certificate) and the
     * known TLS vulnerabilities, independent of what the local JDK still enables.
     *
     * @param hostAliases allowed host names the container resolves to the Docker host, for a target
     *     served on the machine running the tests
     */
    public record Testssl(
            Boolean enabled,
            Boolean testEnvironment,
            String image,
            Duration timeBudget,
            List<String> hostAliases) {
        static final String IMAGE =
                "ghcr.io/testssl/testssl.sh@sha256:53766ec224a2d841b5516bc3923a8586379069ad01c65bbae9244aa24d72a18d";

        public Testssl {
            enabled = Boolean.TRUE.equals(enabled);
            testEnvironment = Boolean.TRUE.equals(testEnvironment);
            image = image == null ? IMAGE : image;
            timeBudget = timeBudget == null ? Duration.ofMinutes(10) : timeBudget;
            hostAliases = List.copyOf(hostAliases == null ? List.of() : hostAliases);
            if (!image.matches("[a-zA-Z0-9./_:-]+@sha256:[a-f0-9]{64}"))
                throw new IllegalArgumentException("testssl.sh image must be pinned by digest");
            if (timeBudget.toSeconds() < 1 || timeBudget.compareTo(Duration.ofMinutes(30)) > 0)
                throw new IllegalArgumentException("testssl.sh time budget must be 1s..30m");
            for (String alias : hostAliases)
                if (!alias.matches("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?"))
                    throw new IllegalArgumentException("testssl.sh host aliases are host names");
        }
    }
}
