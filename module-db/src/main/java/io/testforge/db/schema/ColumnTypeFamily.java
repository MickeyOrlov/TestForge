package io.testforge.db.schema;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

/**
 * Pure mapping enum representing database column type families and their corresponding
 * JDBC and Java types for schema drift validation.
 */
public enum ColumnTypeFamily {
    CHARACTER,
    INTEGER,
    DECIMAL,
    FLOATING,
    BOOLEAN,
    DATE,
    TIME,
    TIMESTAMP,
    BINARY,
    UNKNOWN;

    /**
     * Resolves a {@link ColumnTypeFamily} from a {@link java.sql.Types} integer code as reported
     * by {@code DatabaseMetaData.getColumns} in {@code DATA_TYPE}.
     *
     * @param jdbcType the JDBC type integer code from {@link Types}
     * @return the corresponding {@link ColumnTypeFamily}, or {@link #UNKNOWN} if unmapped
     */
    public static ColumnTypeFamily ofJdbcType(int jdbcType) {
        return switch (jdbcType) {
            case Types.CHAR,
                 Types.VARCHAR,
                 Types.LONGVARCHAR,
                 Types.NCHAR,
                 Types.NVARCHAR,
                 Types.LONGNVARCHAR,
                 Types.CLOB,
                 Types.NCLOB -> CHARACTER;

            case Types.TINYINT,
                 Types.SMALLINT,
                 Types.INTEGER,
                 Types.BIGINT -> INTEGER;

            case Types.DECIMAL,
                 Types.NUMERIC -> DECIMAL;

            case Types.REAL,
                 Types.FLOAT,
                 Types.DOUBLE -> FLOATING;

            // PostgreSQL driver reports bool columns as Types.BIT. Mapping Types.BIT to BOOLEAN
            // prevents false-positive drift when comparing H2 (BOOLEAN) vs PostgreSQL (BIT).
            case Types.BOOLEAN,
                 Types.BIT -> BOOLEAN;

            case Types.DATE -> DATE;

            case Types.TIME,
                 Types.TIME_WITH_TIMEZONE -> TIME;

            case Types.TIMESTAMP,
                 Types.TIMESTAMP_WITH_TIMEZONE -> TIMESTAMP;

            case Types.BINARY,
                 Types.VARBINARY,
                 Types.LONGVARBINARY,
                 Types.BLOB -> BINARY;

            default -> UNKNOWN;
        };
    }

    /**
     * Resolves a {@link ColumnTypeFamily} from a Java entity field.
     *
     * @param field the entity field
     * @return the corresponding {@link ColumnTypeFamily}, or {@link #UNKNOWN} if unmapped or field is null
     */
    public static ColumnTypeFamily ofJavaType(Field field) {
        if (field == null) {
            return UNKNOWN;
        }
        return ofJavaType(field.getType(), field.getAnnotation(Enumerated.class));
    }

    /**
     * Resolves a {@link ColumnTypeFamily} from a Java class without an explicit {@link Enumerated} annotation.
     *
     * @param clazz the Java class
     * @return the corresponding {@link ColumnTypeFamily}, or {@link #UNKNOWN} if unmapped
     */
    public static ColumnTypeFamily ofJavaType(Class<?> clazz) {
        return ofJavaType(clazz, (Enumerated) null);
    }

    /**
     * Resolves a {@link ColumnTypeFamily} from a Java class and optional {@link Enumerated} annotation.
     *
     * @param clazz      the Java class
     * @param enumerated optional {@link Enumerated} annotation for enum types
     * @return the corresponding {@link ColumnTypeFamily}, or {@link #UNKNOWN} if unmapped
     */
    public static ColumnTypeFamily ofJavaType(Class<?> clazz, Enumerated enumerated) {
        if (clazz == null) {
            return UNKNOWN;
        }

        // java.util.UUID MUST resolve to UNKNOWN because JDBC drivers disagree on its representation
        // (e.g. Types.OTHER vs BINARY vs CHAR). Mapping UUID to any concrete family causes false positives.
        if (UUID.class.equals(clazz)) {
            return UNKNOWN;
        }

        if (Enum.class.isAssignableFrom(clazz) && !Enum.class.equals(clazz)) {
            if (enumerated != null && enumerated.value() == EnumType.STRING) {
                return CHARACTER;
            }
            // EnumType.ORDINAL is the JPA default when @Enumerated is absent or specified as ORDINAL
            return INTEGER;
        }

        if (String.class.equals(clazz) || char.class.equals(clazz) || Character.class.equals(clazz)) {
            return CHARACTER;
        }

        if (byte.class.equals(clazz) || Byte.class.equals(clazz)
                || short.class.equals(clazz) || Short.class.equals(clazz)
                || int.class.equals(clazz) || Integer.class.equals(clazz)
                || long.class.equals(clazz) || Long.class.equals(clazz)
                || BigInteger.class.equals(clazz)) {
            return INTEGER;
        }

        if (BigDecimal.class.equals(clazz)) {
            return DECIMAL;
        }

        if (float.class.equals(clazz) || Float.class.equals(clazz)
                || double.class.equals(clazz) || Double.class.equals(clazz)) {
            return FLOATING;
        }

        if (boolean.class.equals(clazz) || Boolean.class.equals(clazz)) {
            return BOOLEAN;
        }

        if (LocalDate.class.equals(clazz) || java.sql.Date.class.equals(clazz)) {
            return DATE;
        }

        if (LocalTime.class.equals(clazz) || java.sql.Time.class.equals(clazz)) {
            return TIME;
        }

        if (Instant.class.equals(clazz) || LocalDateTime.class.equals(clazz)
                || OffsetDateTime.class.equals(clazz) || ZonedDateTime.class.equals(clazz)
                || java.util.Date.class.equals(clazz) || java.sql.Timestamp.class.equals(clazz)) {
            return TIMESTAMP;
        }

        if (byte[].class.equals(clazz)) {
            return BINARY;
        }

        return UNKNOWN;
    }
}
