package io.testforge.db.schema;

import java.util.Locale;

/**
 * Turns implicit JPA names (a field or entity name with no explicit
 * {@code @Column}/{@code @Table} name) into the physical identifier the
 * database uses. Explicit names are taken as written.
 *
 * <p>Match it to the ORM configuration of the service under test. Spring
 * Boot's default ({@code CamelCaseToUnderscoresNamingStrategy}) is
 * {@link #SNAKE_CASE}; Hibernate's JPA-compliant standard naming is
 * {@link #AS_IS}. Anything else is a bean implementing this interface.
 */
@FunctionalInterface
public interface SchemaNamingStrategy {

    /** {@code orderLine} → {@code order_line}; the default. */
    SchemaNamingStrategy SNAKE_CASE = name -> name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);

    /** {@code orderLine} → {@code orderLine}. */
    SchemaNamingStrategy AS_IS = name -> name;

    String physicalName(String logicalName);

    /** Resolves {@code forge.db.schema-naming}: {@code snake-case} (default) or {@code as-is}. */
    static SchemaNamingStrategy named(String name) {
        if (name == null || name.isBlank()) {
            return SNAKE_CASE;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT).replace('_', '-')) {
            case "snake-case" -> SNAKE_CASE;
            case "as-is" -> AS_IS;
            default -> throw new IllegalArgumentException(
                    "Unknown forge.db.schema-naming '%s': use snake-case or as-is, or declare a SchemaNamingStrategy bean"
                            .formatted(name));
        };
    }
}
