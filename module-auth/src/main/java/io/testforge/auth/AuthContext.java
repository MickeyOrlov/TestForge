package io.testforge.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The role the current thread acts as. Set by {@link AsUser} for a whole test,
 * or by {@link #runAs} for a block inside one.
 */
public final class AuthContext {

    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private AuthContext() {
    }

    public static Optional<String> currentRole() {
        return Optional.ofNullable(ROLE.get());
    }

    public static void runAs(String role, Runnable action) {
        callAs(role, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(String role, Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        String previous = ROLE.get();
        set(role);
        try {
            return action.get();
        } finally {
            restore(previous);
        }
    }

    static void set(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("role must not be blank");
        }
        ROLE.set(role);
    }

    static void restore(String previous) {
        if (previous == null) {
            ROLE.remove();
        } else {
            ROLE.set(previous);
        }
    }
}
