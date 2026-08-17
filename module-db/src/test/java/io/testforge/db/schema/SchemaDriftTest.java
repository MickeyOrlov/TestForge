package io.testforge.db.schema;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SchemaDriftTest {

    private static DataSource dataSource;
    private static SchemaValidator validator;

    @Entity
    @Table(name = "healthy_table")
    static class HealthyEntity {
        @Id
        private Long id;

        @Column(name = "name", nullable = false)
        private String name;

        @Column(name = "age")
        private Integer age;
    }

    @Entity
    @Table(name = "length_diff_table")
    static class Varchar50Entity {
        @Id
        private Long id;

        @Column(name = "code", length = 50)
        private String code;
    }

    @Entity
    @Table(name = "silent_not_null_table")
    static class SilentMappingEntity {
        @Id
        private Long id;

        @Column(name = "description")
        private String description;
    }

    static class CustomPayload {
        private String data;
    }

    @Entity
    @Table(name = "unresolvable_type_table")
    static class UnresolvableTypeEntity {
        @Id
        private Long id;

        @Column(name = "uuid_val")
        private UUID uuidVal;

        @Column(name = "custom_val")
        private CustomPayload customVal;
    }

    @Entity
    @Table(name = "relationship_table")
    static class RelationshipEntity {
        @Id
        private Long id;

        @ManyToOne
        @JoinColumn(name = "target_id")
        private HealthyEntity target;
    }

    @Entity
    @Table(name = "type_drift_table")
    static class TypeDriftEntity {
        @Id
        private Long id;

        @Column(name = "task_id")
        private String taskId;
    }

    @Entity
    @Table(name = "nullability_drift_table")
    static class NullabilityDriftEntity {
        @Id
        private Long id;

        @Column(name = "required_code", nullable = false)
        private String requiredCode;
    }

    @Entity
    @Table(name = "id_nullable_table")
    static class IdNullableEntity {
        @Id
        @Column(name = "id")
        private Long id;
    }

    @Entity
    @Table(name = "basic_nullability_table")
    static class BasicAndJoinColumnNotNullEntity {
        @Id
        private Long id;

        @Basic(optional = false)
        @Column(name = "basic_field")
        private String basicField;

        @ManyToOne
        @JoinColumn(name = "parent_id", nullable = false)
        private HealthyEntity parent;
    }

    @Entity
    @Table(name = "combination_table")
    static class DriftCombinationEntity {
        @Id
        private Long id;

        @Column(name = "type_col")
        private String typeCol;

        @Column(name = "null_col", nullable = false)
        private String nullCol;

        @Column(name = "missing_col")
        private String missingCol;
    }

    @Entity
    @Table(name = "nonexistent_table")
    static class MissingTableEntity {
        @Id
        private Long id;

        @Column(name = "col", nullable = false)
        private String col;
    }

    @BeforeAll
    static void setupDatabase() throws Exception {
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:schema_drift_test;DB_CLOSE_DELAY=-1");
        validator = new SchemaValidator(dataSource);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE healthy_table (
                        id BIGINT PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        age INT
                    );

                    CREATE TABLE length_diff_table (
                        id BIGINT PRIMARY KEY,
                        code VARCHAR(255)
                    );

                    CREATE TABLE silent_not_null_table (
                        id BIGINT PRIMARY KEY,
                        description VARCHAR(255) NOT NULL
                    );

                    CREATE TABLE unresolvable_type_table (
                        id BIGINT PRIMARY KEY,
                        uuid_val VARCHAR(36),
                        custom_val INT
                    );

                    CREATE TABLE relationship_table (
                        id BIGINT PRIMARY KEY,
                        target_id VARCHAR(255)
                    );

                    CREATE TABLE type_drift_table (
                        id BIGINT PRIMARY KEY,
                        task_id INT
                    );

                    CREATE TABLE nullability_drift_table (
                        id BIGINT PRIMARY KEY,
                        required_code VARCHAR(255) NULL
                    );

                    CREATE TABLE id_nullable_table (
                        id BIGINT NULL
                    );

                    CREATE TABLE basic_nullability_table (
                        id BIGINT PRIMARY KEY,
                        basic_field VARCHAR(255) NULL,
                        parent_id BIGINT NULL
                    );

                    CREATE TABLE combination_table (
                        id BIGINT PRIMARY KEY,
                        type_col INT,
                        null_col VARCHAR(255) NULL
                    );
                    """);
        }
    }

    // --- NEGATIVE TESTS ---

    @Test
    @DisplayName("A fully healthy schema reports nothing from all three methods")
    void healthySchema_reportsNothing() {
        assertThat(validator.missingColumns(HealthyEntity.class)).isEmpty();
        assertThat(validator.typeDrift(HealthyEntity.class)).isEmpty();
        assertThat(validator.nullabilityDrift(HealthyEntity.class)).isEmpty();
        assertThat(validator.schemaDrift(HealthyEntity.class)).isEmpty();
    }

    @Test
    @DisplayName("VARCHAR(50) in mapping vs VARCHAR(255) in DB reports nothing (length is not drift)")
    void lengthDifference_reportsNothing() {
        assertThat(validator.typeDrift(Varchar50Entity.class)).isEmpty();
        assertThat(validator.nullabilityDrift(Varchar50Entity.class)).isEmpty();
        assertThat(validator.schemaDrift(Varchar50Entity.class)).isEmpty();
    }

    @Test
    @DisplayName("Database NOT NULL column against a silent mapping reports nothing")
    void dbNotNullAgainstSilentMapping_reportsNothing() {
        assertThat(validator.nullabilityDrift(SilentMappingEntity.class)).isEmpty();
        assertThat(validator.schemaDrift(SilentMappingEntity.class)).isEmpty();
    }

    @Test
    @DisplayName("Mapped field of unresolvable type (UUID or custom class) reports nothing")
    void unresolvableType_reportsNothing() {
        assertThat(validator.typeDrift(UnresolvableTypeEntity.class)).isEmpty();
        assertThat(validator.schemaDrift(UnresolvableTypeEntity.class)).isEmpty();
    }

    @Test
    @DisplayName("Relationship columns (@ManyToOne/@JoinColumn) are skipped for type drift")
    void relationshipColumn_skippedForTypeDrift() {
        assertThat(validator.typeDrift(RelationshipEntity.class)).isEmpty();
    }

    @Test
    @DisplayName("missingColumns on a schema that HAS type and nullability drift returns empty list")
    void missingColumns_doesNotReportTypeOrNullabilityDrift() {
        // type_drift_table has type drift, nullability_drift_table has nullability drift
        assertThat(validator.missingColumns(TypeDriftEntity.class)).isEmpty();
        assertThat(validator.missingColumns(NullabilityDriftEntity.class)).isEmpty();
    }

    // --- POSITIVE TESTS ---

    @Test
    @DisplayName("Column family change (String vs INT) is reported by typeDrift with both sides")
    void typeDrift_reportsFamilyMismatch() {
        List<String> findings = validator.typeDrift(TypeDriftEntity.class);

        assertThat(findings)
                .hasSize(1)
                .first()
                .asString()
                .contains("type_drift_table.task_id")
                .contains("mapped as CHARACTER (String)")
                .contains("database column is INTEGER");
    }

    @Test
    @DisplayName("Nullable DB column against @Column(nullable=false) is reported by nullabilityDrift")
    void nullabilityDrift_reportsNullableDbColumn() {
        List<String> findings = validator.nullabilityDrift(NullabilityDriftEntity.class);

        assertThat(findings)
                .containsExactly("nullability_drift_table.required_code: mapping declares NOT NULL but database column is nullable");
    }

    @Test
    @DisplayName("@Id against a nullable database column is reported by nullabilityDrift")
    void nullabilityDrift_reportsNullableIdColumn() {
        List<String> findings = validator.nullabilityDrift(IdNullableEntity.class);

        assertThat(findings)
                .containsExactly("id_nullable_table.id: mapping declares NOT NULL but database column is nullable");
    }

    @Test
    @DisplayName("@Basic(optional=false) and @JoinColumn(nullable=false) are reported by nullabilityDrift")
    void nullabilityDrift_reportsBasicAndJoinColumnNotNull() {
        List<String> findings = validator.nullabilityDrift(BasicAndJoinColumnNotNullEntity.class);

        assertThat(findings)
                .containsExactly(
                        "basic_nullability_table.basic_field: mapping declares NOT NULL but database column is nullable",
                        "basic_nullability_table.parent_id: mapping declares NOT NULL but database column is nullable"
                );
    }

    @Test
    @DisplayName("schemaDrift returns the union of missingColumns, typeDrift, and nullabilityDrift")
    void schemaDrift_returnsUnion() {
        List<String> missing = validator.missingColumns(DriftCombinationEntity.class);
        List<String> type = validator.typeDrift(DriftCombinationEntity.class);
        List<String> nullability = validator.nullabilityDrift(DriftCombinationEntity.class);
        List<String> schema = validator.schemaDrift(DriftCombinationEntity.class);

        assertThat(missing).containsExactly("combination_table.missing_col");
        assertThat(type).hasSize(1);
        assertThat(type.get(0)).contains("combination_table.type_col");
        assertThat(nullability).containsExactly("combination_table.null_col: mapping declares NOT NULL but database column is nullable");

        assertThat(schema)
                .containsExactly(missing.get(0), type.get(0), nullability.get(0));
    }

    @Test
    @DisplayName("Missing table yields ONLY table-not-found message from schemaDrift")
    void missingTable_yieldsOnlyTableNotFoundMessage() {
        List<String> missing = validator.missingColumns(MissingTableEntity.class);
        List<String> type = validator.typeDrift(MissingTableEntity.class);
        List<String> nullability = validator.nullabilityDrift(MissingTableEntity.class);
        List<String> schema = validator.schemaDrift(MissingTableEntity.class);

        assertThat(missing).containsExactly("table 'nonexistent_table' not found in database");
        assertThat(type).isEmpty();
        assertThat(nullability).isEmpty();
        assertThat(schema).containsExactly("table 'nonexistent_table' not found in database");
    }
}
