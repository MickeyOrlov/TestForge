package io.testforge.security;

import java.util.HashSet;
import java.util.Set;

/**
 * Process-memory-only known secrets, used only for exact leak detection. Never serialized or
 * logged.
 */
public final class SecuritySecretRegistry {
    private final Set<String> values = new HashSet<>();
    private boolean overflow;

    public synchronized void register(String value) {
        if (value == null || value.isBlank() || value.equals("***")) return;
        if (values.size() >= 1000 || value.length() > 16384) {
            overflow = true;
            return;
        }
        values.add(value);
    }

    synchronized boolean matches(String content) {
        return values.stream().anyMatch(content::contains);
    }

    synchronized boolean complete() {
        return !overflow;
    }

    @Override
    public String toString() {
        return "SecuritySecretRegistry[redacted]";
    }
}
