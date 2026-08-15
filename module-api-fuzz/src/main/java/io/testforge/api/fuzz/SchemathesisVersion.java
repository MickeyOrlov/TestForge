package io.testforge.api.fuzz;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SchemathesisVersion(int major, int minor, int patch, String raw) {

    /**
     * Deliberately anchored on {@code version <n.n.n>} rather than on the
     * executable name. {@code st --version} prints {@code st, version 4.24.3}
     * but {@code schemathesis --version} prints
     * {@code schemathesis, version 4.24.3}, and both are valid values for
     * {@code forge.api-fuzz.command}. Used with {@code find()}, not
     * {@code matches()}, so a build that appends its own detail (a Python
     * version, a build tag) still parses.
     */
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s+(\\d+)\\.(\\d+)\\.(\\d+)");

    public static SchemathesisVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiFuzzException("Failed to parse Schemathesis version: no output from the executable");
        }
        Matcher matcher = VERSION_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            throw new ApiFuzzException("Failed to parse Schemathesis version from: " + raw.trim());
        }
        return new SchemathesisVersion(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3)),
                raw.trim()
        );
    }

    public String semver() {
        return major + "." + minor + "." + patch;
    }

    public boolean isSupported() {
        return major >= 4;
    }
}

