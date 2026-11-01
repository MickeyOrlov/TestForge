package io.testforge.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "forge.security")
public record SecurityProperties(
        Passive passive,
        String outputDir,
        String baselineFile,
        List<String> acceptedFindings,
        Set<Severity> failOn,
        Integer maxEntries) {
    public SecurityProperties {
        passive = passive == null ? new Passive(null, null, null, null, null, null) : passive;
        outputDir = outputDir == null ? "build/security" : outputDir;
        acceptedFindings = List.copyOf(acceptedFindings == null ? List.of() : acceptedFindings);
        failOn = Set.copyOf(failOn == null ? Set.of(Severity.CRITICAL, Severity.HIGH) : failOn);
        maxEntries = maxEntries == null ? 10000 : maxEntries;
        if (maxEntries < 1)
            throw new IllegalArgumentException("forge.security.max-entries must be positive");
        for (String accepted : acceptedFindings) {
            int split = accepted.indexOf(" # ");
            if (split < 1 || accepted.substring(split + 3).isBlank())
                throw new IllegalArgumentException(
                        "accepted-findings requires '<finding id> # <reason>'");
        }
        if (baselineFile != null
                && Path.of(baselineFile)
                        .toAbsolutePath()
                        .normalize()
                        .equals(
                                Path.of(outputDir, "security-findings.json")
                                        .toAbsolutePath()
                                        .normalize()))
            throw new IllegalArgumentException("baseline-file must differ from the report path");
    }

    public record Passive(
            Boolean enabled,
            List<String> sessionCookies,
            List<String> forbiddenFields,
            List<String> allowedSensitivePaths,
            List<String> untrustedOrigins,
            Integer maxBodyChars) {
        public Passive {
            enabled = Boolean.TRUE.equals(enabled);
            sessionCookies =
                    List.copyOf(
                            sessionCookies == null
                                    ? List.of("SESSION", "JSESSIONID")
                                    : sessionCookies);
            forbiddenFields = List.copyOf(forbiddenFields == null ? List.of() : forbiddenFields);
            allowedSensitivePaths =
                    List.copyOf(allowedSensitivePaths == null ? List.of() : allowedSensitivePaths);
            untrustedOrigins = List.copyOf(untrustedOrigins == null ? List.of() : untrustedOrigins);
            maxBodyChars = maxBodyChars == null ? 262144 : maxBodyChars;
            if (maxBodyChars < 1)
                throw new IllegalArgumentException("passive.max-body-chars must be positive");
        }
    }
}
