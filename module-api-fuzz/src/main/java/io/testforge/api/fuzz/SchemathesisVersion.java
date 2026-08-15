package io.testforge.api.fuzz;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record SchemathesisVersion(int major, int minor, int patch, String raw) {

    private static final Pattern VERSION_PATTERN = Pattern.compile("st, version (\\d+)\\.(\\d+)\\.(\\d+)");

    public static SchemathesisVersion parse(String raw) {
        Matcher matcher = VERSION_PATTERN.matcher(raw.trim());
        if (!matcher.matches()) {
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

