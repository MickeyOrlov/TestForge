package io.testforge.security;

import com.fasterxml.jackson.annotation.JsonValue;

public enum Severity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    INFO;

    @JsonValue
    public String value() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
