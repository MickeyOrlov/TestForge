package io.testforge.data;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * Mask presets for test data generation. Combine with {@link RunUniqueValues}
 * to guarantee per-run uniqueness:
 *
 * <pre>{@code
 * String phone = uniqueValues.generate("phone", Generators.phone("+8499", 7), 100);
 * }</pre>
 */
public final class Generators {

    private static final String ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyz0123456789";

    private Generators() {
    }

    public static Supplier<String> guid() {
        return () -> UUID.randomUUID().toString();
    }

    /** Fixed-length digit string; the first digit is never zero. */
    public static Supplier<String> numeric(int length) {
        requirePositive(length);
        return () -> {
            StringBuilder digits = new StringBuilder(length);
            digits.append(ThreadLocalRandom.current().nextInt(1, 10));
            for (int i = 1; i < length; i++) {
                digits.append(ThreadLocalRandom.current().nextInt(0, 10));
            }
            return digits.toString();
        };
    }

    /** Prefix plus a fixed number of random digits, e.g. {@code +8499} + 7. */
    public static Supplier<String> phone(String prefix, int digits) {
        requirePositive(digits);
        Supplier<String> tail = numeric(digits);
        return () -> prefix + tail.get();
    }

    public static Supplier<String> alphanumeric(int length) {
        requirePositive(length);
        return () -> {
            StringBuilder value = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                value.append(ALPHANUMERIC.charAt(
                        ThreadLocalRandom.current().nextInt(ALPHANUMERIC.length())));
            }
            return value.toString();
        };
    }

    private static void requirePositive(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
    }
}
