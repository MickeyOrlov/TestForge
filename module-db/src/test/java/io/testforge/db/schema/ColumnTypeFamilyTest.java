package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ColumnTypeFamilyTest {

    private enum TestStatus {
        ACTIVE,
        INACTIVE
    }

    private static class DummyEntity {
        @Enumerated(EnumType.STRING)
        private TestStatus stringEnum;

        @Enumerated(EnumType.ORDINAL)
        private TestStatus ordinalEnum;

        private TestStatus defaultEnum;

        private String stringField;

        private CustomType customTypeField;
    }

    private static class CustomType {
        private String value;
    }

    @ParameterizedTest(name = "JDBC type {0} -> {1}")
    @MethodSource("jdbcTypesMapping")
    @DisplayName("Maps JDBC java.sql.Types constants to expected ColumnTypeFamily")
    void ofJdbcType_mapsCorrectly(int jdbcType, ColumnTypeFamily expectedFamily) {
        assertThat(ColumnTypeFamily.ofJdbcType(jdbcType)).isEqualTo(expectedFamily);
    }

    static Stream<Arguments> jdbcTypesMapping() {
        return Stream.of(
                // CHARACTER
                Arguments.of(Types.CHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.VARCHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.LONGVARCHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.NCHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.NVARCHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.LONGNVARCHAR, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.CLOB, ColumnTypeFamily.CHARACTER),
                Arguments.of(Types.NCLOB, ColumnTypeFamily.CHARACTER),

                // INTEGER
                Arguments.of(Types.TINYINT, ColumnTypeFamily.INTEGER),
                Arguments.of(Types.SMALLINT, ColumnTypeFamily.INTEGER),
                Arguments.of(Types.INTEGER, ColumnTypeFamily.INTEGER),
                Arguments.of(Types.BIGINT, ColumnTypeFamily.INTEGER),

                // DECIMAL
                Arguments.of(Types.DECIMAL, ColumnTypeFamily.DECIMAL),
                Arguments.of(Types.NUMERIC, ColumnTypeFamily.DECIMAL),

                // FLOATING
                Arguments.of(Types.REAL, ColumnTypeFamily.FLOATING),
                Arguments.of(Types.FLOAT, ColumnTypeFamily.FLOATING),
                Arguments.of(Types.DOUBLE, ColumnTypeFamily.FLOATING),

                // BOOLEAN
                Arguments.of(Types.BOOLEAN, ColumnTypeFamily.BOOLEAN),
                Arguments.of(Types.BIT, ColumnTypeFamily.BOOLEAN),

                // DATE
                Arguments.of(Types.DATE, ColumnTypeFamily.DATE),

                // TIME
                Arguments.of(Types.TIME, ColumnTypeFamily.TIME),
                Arguments.of(Types.TIME_WITH_TIMEZONE, ColumnTypeFamily.TIME),

                // TIMESTAMP
                Arguments.of(Types.TIMESTAMP, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(Types.TIMESTAMP_WITH_TIMEZONE, ColumnTypeFamily.TIMESTAMP),

                // BINARY
                Arguments.of(Types.BINARY, ColumnTypeFamily.BINARY),
                Arguments.of(Types.VARBINARY, ColumnTypeFamily.BINARY),
                Arguments.of(Types.LONGVARBINARY, ColumnTypeFamily.BINARY),
                Arguments.of(Types.BLOB, ColumnTypeFamily.BINARY),

                // UNKNOWN
                Arguments.of(Types.OTHER, ColumnTypeFamily.UNKNOWN),
                Arguments.of(Types.JAVA_OBJECT, ColumnTypeFamily.UNKNOWN),
                Arguments.of(9999, ColumnTypeFamily.UNKNOWN)
        );
    }

    @ParameterizedTest(name = "Java type {0} -> {1}")
    @MethodSource("javaTypesMapping")
    @DisplayName("Maps Java classes to expected ColumnTypeFamily")
    void ofJavaType_mapsClassCorrectly(Class<?> clazz, ColumnTypeFamily expectedFamily) {
        assertThat(ColumnTypeFamily.ofJavaType(clazz)).isEqualTo(expectedFamily);
    }

    static Stream<Arguments> javaTypesMapping() {
        return Stream.of(
                // CHARACTER
                Arguments.of(String.class, ColumnTypeFamily.CHARACTER),
                Arguments.of(char.class, ColumnTypeFamily.CHARACTER),
                Arguments.of(Character.class, ColumnTypeFamily.CHARACTER),

                // INTEGER
                Arguments.of(byte.class, ColumnTypeFamily.INTEGER),
                Arguments.of(Byte.class, ColumnTypeFamily.INTEGER),
                Arguments.of(short.class, ColumnTypeFamily.INTEGER),
                Arguments.of(Short.class, ColumnTypeFamily.INTEGER),
                Arguments.of(int.class, ColumnTypeFamily.INTEGER),
                Arguments.of(Integer.class, ColumnTypeFamily.INTEGER),
                Arguments.of(long.class, ColumnTypeFamily.INTEGER),
                Arguments.of(Long.class, ColumnTypeFamily.INTEGER),
                Arguments.of(BigInteger.class, ColumnTypeFamily.INTEGER),

                // DECIMAL
                Arguments.of(BigDecimal.class, ColumnTypeFamily.DECIMAL),

                // FLOATING
                Arguments.of(float.class, ColumnTypeFamily.FLOATING),
                Arguments.of(Float.class, ColumnTypeFamily.FLOATING),
                Arguments.of(double.class, ColumnTypeFamily.FLOATING),
                Arguments.of(Double.class, ColumnTypeFamily.FLOATING),

                // BOOLEAN
                Arguments.of(boolean.class, ColumnTypeFamily.BOOLEAN),
                Arguments.of(Boolean.class, ColumnTypeFamily.BOOLEAN),

                // DATE
                Arguments.of(LocalDate.class, ColumnTypeFamily.DATE),
                Arguments.of(java.sql.Date.class, ColumnTypeFamily.DATE),

                // TIME
                Arguments.of(LocalTime.class, ColumnTypeFamily.TIME),
                Arguments.of(java.sql.Time.class, ColumnTypeFamily.TIME),

                // TIMESTAMP
                Arguments.of(Instant.class, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(LocalDateTime.class, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(OffsetDateTime.class, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(ZonedDateTime.class, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(java.util.Date.class, ColumnTypeFamily.TIMESTAMP),
                Arguments.of(java.sql.Timestamp.class, ColumnTypeFamily.TIMESTAMP),

                // BINARY
                Arguments.of(byte[].class, ColumnTypeFamily.BINARY),

                // UNKNOWN
                Arguments.of(UUID.class, ColumnTypeFamily.UNKNOWN),
                Arguments.of(CustomType.class, ColumnTypeFamily.UNKNOWN)
        );
    }

    @Test
    @DisplayName("Types.BIT specifically maps to BOOLEAN for PostgreSQL compatibility")
    void bitMapsToBoolean() {
        assertThat(ColumnTypeFamily.ofJdbcType(Types.BIT)).isEqualTo(ColumnTypeFamily.BOOLEAN);
    }

    @Test
    @DisplayName("Types.OTHER maps to UNKNOWN")
    void typesOtherMapsToUnknown() {
        assertThat(ColumnTypeFamily.ofJdbcType(Types.OTHER)).isEqualTo(ColumnTypeFamily.UNKNOWN);
    }

    @Test
    @DisplayName("Unmapped custom class maps to UNKNOWN")
    void customTypeMapsToUnknown() {
        assertThat(ColumnTypeFamily.ofJavaType(CustomType.class)).isEqualTo(ColumnTypeFamily.UNKNOWN);
    }

    @Test
    @DisplayName("java.util.UUID maps to UNKNOWN")
    void uuidMapsToUnknown() {
        assertThat(ColumnTypeFamily.ofJavaType(UUID.class)).isEqualTo(ColumnTypeFamily.UNKNOWN);
    }

    @Test
    @DisplayName("Primitives and boxed types resolve identically")
    void primitivesAndBoxedTypesResolveIdentically() {
        assertThat(ColumnTypeFamily.ofJavaType(boolean.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Boolean.class))
                .isEqualTo(ColumnTypeFamily.BOOLEAN);

        assertThat(ColumnTypeFamily.ofJavaType(byte.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Byte.class))
                .isEqualTo(ColumnTypeFamily.INTEGER);

        assertThat(ColumnTypeFamily.ofJavaType(short.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Short.class))
                .isEqualTo(ColumnTypeFamily.INTEGER);

        assertThat(ColumnTypeFamily.ofJavaType(int.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Integer.class))
                .isEqualTo(ColumnTypeFamily.INTEGER);

        assertThat(ColumnTypeFamily.ofJavaType(long.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Long.class))
                .isEqualTo(ColumnTypeFamily.INTEGER);

        assertThat(ColumnTypeFamily.ofJavaType(float.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Float.class))
                .isEqualTo(ColumnTypeFamily.FLOATING);

        assertThat(ColumnTypeFamily.ofJavaType(double.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Double.class))
                .isEqualTo(ColumnTypeFamily.FLOATING);

        assertThat(ColumnTypeFamily.ofJavaType(char.class))
                .isEqualTo(ColumnTypeFamily.ofJavaType(Character.class))
                .isEqualTo(ColumnTypeFamily.CHARACTER);
    }

    @Test
    @DisplayName("Enum with @Enumerated(EnumType.STRING) resolves to CHARACTER")
    void enumWithStringResolvesToCharacter() throws NoSuchFieldException {
        Field stringEnumField = DummyEntity.class.getDeclaredField("stringEnum");
        assertThat(ColumnTypeFamily.ofJavaType(stringEnumField)).isEqualTo(ColumnTypeFamily.CHARACTER);

        Enumerated stringAnn = stringEnumField.getAnnotation(Enumerated.class);
        assertThat(ColumnTypeFamily.ofJavaType(TestStatus.class, stringAnn)).isEqualTo(ColumnTypeFamily.CHARACTER);
    }

    @Test
    @DisplayName("Enum with @Enumerated(EnumType.ORDINAL) resolves to INTEGER")
    void enumWithOrdinalResolvesToInteger() throws NoSuchFieldException {
        Field ordinalEnumField = DummyEntity.class.getDeclaredField("ordinalEnum");
        assertThat(ColumnTypeFamily.ofJavaType(ordinalEnumField)).isEqualTo(ColumnTypeFamily.INTEGER);

        Enumerated ordinalAnn = ordinalEnumField.getAnnotation(Enumerated.class);
        assertThat(ColumnTypeFamily.ofJavaType(TestStatus.class, ordinalAnn)).isEqualTo(ColumnTypeFamily.INTEGER);
    }

    @Test
    @DisplayName("Enum with no @Enumerated resolves to INTEGER (JPA default)")
    void enumWithoutEnumeratedResolvesToInteger() throws NoSuchFieldException {
        Field defaultEnumField = DummyEntity.class.getDeclaredField("defaultEnum");
        assertThat(ColumnTypeFamily.ofJavaType(defaultEnumField)).isEqualTo(ColumnTypeFamily.INTEGER);
        assertThat(ColumnTypeFamily.ofJavaType(TestStatus.class)).isEqualTo(ColumnTypeFamily.INTEGER);
        assertThat(ColumnTypeFamily.ofJavaType(TestStatus.class, (Enumerated) null)).isEqualTo(ColumnTypeFamily.INTEGER);
    }

    @Test
    @DisplayName("Null class, null field, or null inputs return UNKNOWN")
    void nullInputsReturnUnknown() {
        assertThat(ColumnTypeFamily.ofJavaType((Class<?>) null)).isEqualTo(ColumnTypeFamily.UNKNOWN);
        assertThat(ColumnTypeFamily.ofJavaType((Field) null)).isEqualTo(ColumnTypeFamily.UNKNOWN);
        assertThat(ColumnTypeFamily.ofJavaType(null, null)).isEqualTo(ColumnTypeFamily.UNKNOWN);
    }
}
