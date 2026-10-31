package io.testforge.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The role the current thread acts as. Set by {@link AsUser} for a whole test,
 * or by {@link #runAs} for a block inside one.
 *
 * <p>{@link #ANONYMOUS} is a reserved role: acting as it sends no token at all,
 * even when {@code forge.auth.default-role} is set. That is how a test asks
 * "what does a caller without credentials get?".
 */
public final class AuthContext {

    /** Reserved role name: no token, and the default role does not apply either. */
    public static final String ANONYMOUS = "anonymous";

    private static final ThreadLocal<String> ROLE = new ThreadLocal<>();

    private AuthContext() {
    }

    /** The role whose token requests carry; empty outside any role and for {@link #ANONYMOUS}. */
    public static Optional<String> currentRole() {
        return Optional.ofNullable(ROLE.get()).filter(role -> !ANONYMOUS.equals(role));
    }

    /** True when the thread explicitly acts as {@link #ANONYMOUS}. */
    public static boolean anonymous() {
        return ANONYMOUS.equals(ROLE.get());
    }

    /** The raw value, {@link #ANONYMOUS} included; for restoring it later. */
    static Optional<String> current() {
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
